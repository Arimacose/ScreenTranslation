#include <chrono>
#include <cstdlib>
#include <cstdint>
#include <fstream>
#include <future>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include <unistd.h>

#include "common/logging.h"
#include "translator/parser.h"
#include "translator/project_version.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

#ifndef SCREENTRANSLATION_BERGAMOT_COMMIT
#define SCREENTRANSLATION_BERGAMOT_COMMIT "unknown"
#endif

namespace {

using Clock = std::chrono::steady_clock;
using marian::bergamot::AsyncService;
using marian::bergamot::BlockingService;
using marian::bergamot::ConcatStrategy;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

struct Arguments {
  std::string configPath;
  std::string inputPath;
  size_t repetitions{3};
  size_t workers{1};
  std::string service{"blocking"};
  std::string warmupText{"Warm-up sentence."};
};

struct Group {
  std::string id;
  std::vector<std::string> texts;
};

struct MemoryStats {
  uint64_t rssKiB{0};
  uint64_t highWaterKiB{0};
};

double elapsedMilliseconds(Clock::time_point started) {
  return std::chrono::duration<double, std::milli>(Clock::now() - started)
      .count();
}

std::string jsonEscape(const std::string& value) {
  static constexpr char kHex[] = "0123456789ABCDEF";
  std::string escaped;
  escaped.reserve(value.size() + 16);
  for (unsigned char character : value) {
    switch (character) {
      case '"':
        escaped += "\\\"";
        break;
      case '\\':
        escaped += "\\\\";
        break;
      case '\b':
        escaped += "\\b";
        break;
      case '\f':
        escaped += "\\f";
        break;
      case '\n':
        escaped += "\\n";
        break;
      case '\r':
        escaped += "\\r";
        break;
      case '\t':
        escaped += "\\t";
        break;
      default:
        if (character < 0x20) {
          escaped += "\\u00";
          escaped += kHex[(character >> 4) & 0x0F];
          escaped += kHex[character & 0x0F];
        } else {
          escaped += static_cast<char>(character);
        }
    }
  }
  return escaped;
}

MemoryStats readMemoryStats() {
  std::ifstream status("/proc/self/status");
  MemoryStats stats;
  std::string line;
  while (std::getline(status, line)) {
    if (line.rfind("VmRSS:", 0) == 0 || line.rfind("VmHWM:", 0) == 0) {
      std::istringstream fields(line);
      std::string key;
      uint64_t value = 0;
      std::string unit;
      fields >> key >> value >> unit;
      if (key == "VmRSS:") {
        stats.rssKiB = value;
      } else if (key == "VmHWM:") {
        stats.highWaterKiB = value;
      }
    }
  }
  return stats;
}

size_t parsePositiveSize(const std::string& value, const std::string& option) {
  size_t consumed = 0;
  const unsigned long long parsed = std::stoull(value, &consumed);
  if (consumed != value.size() || parsed == 0) {
    throw std::invalid_argument(option + " must be a positive integer");
  }
  return static_cast<size_t>(parsed);
}

Arguments parseArguments(int argc, char** argv) {
  Arguments arguments;
  for (int index = 1; index < argc; ++index) {
    const std::string option(argv[index]);
    auto nextValue = [&]() -> std::string {
      if (++index >= argc) {
        throw std::invalid_argument("Missing value for " + option);
      }
      return argv[index];
    };

    if (option == "--config") {
      arguments.configPath = nextValue();
    } else if (option == "--input") {
      arguments.inputPath = nextValue();
    } else if (option == "--repetitions") {
      arguments.repetitions = parsePositiveSize(nextValue(), option);
    } else if (option == "--workers") {
      arguments.workers = parsePositiveSize(nextValue(), option);
    } else if (option == "--service") {
      arguments.service = nextValue();
      if (arguments.service != "blocking" &&
          arguments.service != "async") {
        throw std::invalid_argument(
            "--service must be blocking or async");
      }
    } else if (option == "--warmup-text") {
      arguments.warmupText = nextValue();
    } else if (option == "--help" || option == "-h") {
      std::cout
          << "Usage: bergamot-android-benchmark --config FILE --input FILE "
             "[--repetitions N] [--workers N] "
             "[--service blocking|async] [--warmup-text TEXT]\n";
      std::exit(0);
    } else {
      throw std::invalid_argument("Unknown option: " + option);
    }
  }

  if (arguments.configPath.empty()) {
    throw std::invalid_argument("--config is required");
  }
  if (arguments.inputPath.empty()) {
    throw std::invalid_argument("--input is required");
  }
  return arguments;
}

std::vector<Group> readGroups(const std::string& path) {
  std::ifstream input(path);
  if (!input) {
    throw std::runtime_error("Failed to open input: " + path);
  }

  std::vector<Group> groups;
  std::string line;
  size_t lineNumber = 0;
  while (std::getline(input, line)) {
    ++lineNumber;
    if (!line.empty() && line.back() == '\r') {
      line.pop_back();
    }
    if (line.empty()) {
      continue;
    }
    const size_t separator = line.find('\t');
    if (separator == std::string::npos || separator == 0 ||
        separator + 1 >= line.size()) {
      throw std::runtime_error(
          "Expected group_id<TAB>text at line " + std::to_string(lineNumber));
    }

    const std::string id = line.substr(0, separator);
    const std::string text = line.substr(separator + 1);
    if (groups.empty() || groups.back().id != id) {
      for (const Group& existing : groups) {
        if (existing.id == id) {
          throw std::runtime_error(
              "Group rows must be contiguous: " + id);
        }
      }
      groups.push_back(Group{id, {}});
    }
    groups.back().texts.push_back(text);
  }
  if (groups.empty()) {
    throw std::runtime_error("Input contains no translation groups");
  }
  return groups;
}

ResponseOptions responseOptions() {
  ResponseOptions options;
  options.qualityScores = false;
  options.alignment = false;
  options.HTML = false;
  options.sentenceMappings = false;
  options.concatStrategy = ConcatStrategy::SPACE;
  return options;
}

std::vector<Response> translateAsync(
    AsyncService& service,
    const std::shared_ptr<TranslationModel>& model,
    const std::vector<std::string>& texts) {
  std::vector<std::promise<Response>> promises(texts.size());
  std::vector<std::future<Response>> futures;
  futures.reserve(texts.size());

  const ResponseOptions options = responseOptions();

  for (size_t index = 0; index < texts.size(); ++index) {
    futures.push_back(promises[index].get_future());
    service.translate(
        model,
        std::string(texts[index]),
        [&promise = promises[index]](Response&& response) {
          promise.set_value(std::move(response));
        },
        options);
  }

  std::vector<Response> responses;
  responses.reserve(texts.size());
  for (std::future<Response>& future : futures) {
    responses.push_back(future.get());
  }
  return responses;
}

std::vector<Response> translateBlocking(
    BlockingService& service,
    const std::shared_ptr<TranslationModel>& model,
    const std::vector<std::string>& texts) {
  std::vector<std::string> sources(texts);
  std::vector<ResponseOptions> options(
      texts.size(),
      responseOptions());
  return service.translateMultiple(
      model,
      std::move(sources),
      options);
}

void writeMeta(
    const Arguments& arguments,
    double serviceMs,
    double modelMs,
    double warmupMs) {
  const MemoryStats memory = readMemoryStats();
  std::cout << "{\"kind\":\"meta\""
            << ",\"bergamot_version\":\""
            << jsonEscape(marian::bergamot::bergamotBuildVersion()) << "\""
            << ",\"bergamot_commit\":\""
            << SCREENTRANSLATION_BERGAMOT_COMMIT << "\""
            << ",\"pid\":" << getpid()
            << ",\"service\":\"" << arguments.service << "\""
            << ",\"workers\":" << arguments.workers
            << ",\"repetitions\":" << arguments.repetitions
            << ",\"service_ms\":" << std::fixed << std::setprecision(3)
            << serviceMs
            << ",\"model_ms\":" << modelMs
            << ",\"warmup_ms\":" << warmupMs
            << ",\"rss_kib\":" << memory.rssKiB
            << ",\"hwm_kib\":" << memory.highWaterKiB
            << "}\n";
}

void writeMeasurement(
    const Group& group,
    size_t repetition,
    double latencyMs,
    const std::vector<Response>& responses) {
  const MemoryStats memory = readMemoryStats();
  std::cout << "{\"kind\":\"measurement\""
            << ",\"group_id\":\"" << jsonEscape(group.id) << "\""
            << ",\"repetition\":" << repetition
            << ",\"latency_ms\":" << std::fixed << std::setprecision(3)
            << latencyMs
            << ",\"outputs\":[";
  for (size_t index = 0; index < responses.size(); ++index) {
    if (index > 0) {
      std::cout << ',';
    }
    std::cout << '"' << jsonEscape(responses[index].target.text) << '"';
  }
  std::cout << "]"
            << ",\"rss_kib\":" << memory.rssKiB
            << ",\"hwm_kib\":" << memory.highWaterKiB
            << "}\n";
}

template <class TranslateFunction>
void runSuite(
    const Arguments& arguments,
    const std::vector<Group>& groups,
    double serviceMs,
    double modelMs,
    TranslateFunction translateFunction) {
  const Clock::time_point warmupStarted = Clock::now();
  translateFunction(std::vector<std::string>{arguments.warmupText});
  const double warmupMs = elapsedMilliseconds(warmupStarted);
  writeMeta(arguments, serviceMs, modelMs, warmupMs);

  const Clock::time_point benchmarkStarted = Clock::now();
  for (const Group& group : groups) {
    for (size_t repetition = 0; repetition < arguments.repetitions;
         ++repetition) {
      const Clock::time_point started = Clock::now();
      const std::vector<Response> responses =
          translateFunction(group.texts);
      writeMeasurement(
          group,
          repetition,
          elapsedMilliseconds(started),
          responses);
    }
  }

  const MemoryStats memory = readMemoryStats();
  std::cout << "{\"kind\":\"summary\""
            << ",\"groups\":" << groups.size()
            << ",\"elapsed_ms\":" << std::fixed << std::setprecision(3)
            << elapsedMilliseconds(benchmarkStarted)
            << ",\"rss_kib\":" << memory.rssKiB
            << ",\"hwm_kib\":" << memory.highWaterKiB
            << "}\n";
}

}  // namespace

int main(int argc, char** argv) {
  try {
    const Arguments arguments = parseArguments(argc, argv);
    const std::vector<Group> groups = readGroups(arguments.inputPath);
    marian::setThrowExceptionOnAbort(true);

    const auto modelConfig =
        marian::bergamot::parseOptionsFromFilePath(arguments.configPath);

    if (arguments.service == "blocking") {
      BlockingService::Config serviceConfig;
      serviceConfig.cacheSize = 0;
      serviceConfig.logger.level = "off";
      const Clock::time_point serviceStarted = Clock::now();
      BlockingService service(serviceConfig);
      const double serviceMs = elapsedMilliseconds(serviceStarted);

      const Clock::time_point modelStarted = Clock::now();
      const std::shared_ptr<TranslationModel> model =
          std::make_shared<TranslationModel>(modelConfig);
      const double modelMs = elapsedMilliseconds(modelStarted);
      runSuite(
          arguments,
          groups,
          serviceMs,
          modelMs,
          [&](const std::vector<std::string>& texts) {
            return translateBlocking(service, model, texts);
          });
    } else {
      AsyncService::Config serviceConfig;
      serviceConfig.numWorkers = arguments.workers;
      serviceConfig.cacheSize = 0;
      serviceConfig.logger.level = "off";
      const Clock::time_point serviceStarted = Clock::now();
      AsyncService service(serviceConfig);
      const double serviceMs = elapsedMilliseconds(serviceStarted);

      const Clock::time_point modelStarted = Clock::now();
      const std::shared_ptr<TranslationModel> model =
          service.createCompatibleModel(modelConfig);
      const double modelMs = elapsedMilliseconds(modelStarted);
      runSuite(
          arguments,
          groups,
          serviceMs,
          modelMs,
          [&](const std::vector<std::string>& texts) {
            return translateAsync(service, model, texts);
          });
    }
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "{\"kind\":\"error\",\"message\":\""
              << jsonEscape(error.what()) << "\"}\n";
    return 1;
  }
}
