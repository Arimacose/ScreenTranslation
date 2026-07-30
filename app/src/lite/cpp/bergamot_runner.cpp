#include <cstdlib>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "common/logging.h"
#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

namespace {

using marian::bergamot::BlockingService;
using marian::bergamot::ConcatStrategy;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

constexpr char kBase64Alphabet[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64Encode(const std::string& input) {
  std::string output;
  output.reserve(((input.size() + 2) / 3) * 4);
  size_t index = 0;
  while (index < input.size()) {
    const unsigned int first =
        static_cast<unsigned char>(input[index++]);
    const bool hasSecond = index < input.size();
    const unsigned int second =
        hasSecond ? static_cast<unsigned char>(input[index++]) : 0;
    const bool hasThird = index < input.size();
    const unsigned int third =
        hasThird ? static_cast<unsigned char>(input[index++]) : 0;
    const unsigned int triple = (first << 16) | (second << 8) | third;
    output += kBase64Alphabet[(triple >> 18) & 0x3f];
    output += kBase64Alphabet[(triple >> 12) & 0x3f];
    output += hasSecond ? kBase64Alphabet[(triple >> 6) & 0x3f] : '=';
    output += hasThird ? kBase64Alphabet[triple & 0x3f] : '=';
  }
  return output;
}

int base64Value(const char value) {
  if (value >= 'A' && value <= 'Z') return value - 'A';
  if (value >= 'a' && value <= 'z') return value - 'a' + 26;
  if (value >= '0' && value <= '9') return value - '0' + 52;
  if (value == '+') return 62;
  if (value == '/') return 63;
  return -1;
}

std::string base64Decode(const std::string& input) {
  if (input.size() % 4 != 0) {
    throw std::invalid_argument("invalid base64 length");
  }
  std::string output;
  output.reserve((input.size() / 4) * 3);
  for (size_t index = 0; index < input.size(); index += 4) {
    int values[4]{};
    for (size_t offset = 0; offset < 4; ++offset) {
      const char character = input[index + offset];
      values[offset] = character == '=' ? 0 : base64Value(character);
      if (values[offset] < 0) {
        throw std::invalid_argument("invalid base64 character");
      }
    }
    const unsigned int triple =
        (static_cast<unsigned int>(values[0]) << 18) |
        (static_cast<unsigned int>(values[1]) << 12) |
        (static_cast<unsigned int>(values[2]) << 6) |
        static_cast<unsigned int>(values[3]);
    output += static_cast<char>((triple >> 16) & 0xff);
    if (input[index + 2] != '=') {
      output += static_cast<char>((triple >> 8) & 0xff);
    }
    if (input[index + 3] != '=') {
      output += static_cast<char>(triple & 0xff);
    }
  }
  return output;
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

std::string translate(
    BlockingService& service,
    const std::vector<std::shared_ptr<TranslationModel>>& models,
    const std::string& source) {
  std::string current = source;
  for (const auto& model : models) {
    std::vector<std::string> sources{current};
    std::vector<ResponseOptions> options{responseOptions()};
    std::vector<Response> responses =
        service.translateMultiple(model, std::move(sources), options);
    if (responses.size() != 1) {
      throw std::runtime_error("unexpected Bergamot response count");
    }
    current = std::move(responses.front().target.text);
  }
  return current;
}

std::vector<std::string> parseConfigs(int argc, char** argv) {
  std::vector<std::string> configs;
  for (int index = 1; index < argc; ++index) {
    const std::string option(argv[index]);
    if (option != "--config" || ++index >= argc) {
      throw std::invalid_argument(
          "usage: bergamot_runner --config FILE [--config FILE]");
    }
    configs.emplace_back(argv[index]);
  }
  if (configs.empty()) {
    throw std::invalid_argument("at least one --config is required");
  }
  return configs;
}

}  // namespace

int main(int argc, char** argv) {
  try {
    const std::vector<std::string> configs = parseConfigs(argc, argv);
    marian::setThrowExceptionOnAbort(true);

    BlockingService::Config serviceConfig;
    serviceConfig.cacheSize = 64;
    serviceConfig.logger.level = "off";
    BlockingService service(serviceConfig);

    std::vector<std::shared_ptr<TranslationModel>> models;
    for (const std::string& config : configs) {
      models.push_back(std::make_shared<TranslationModel>(
          marian::bergamot::parseOptionsFromFilePath(config)));
    }

    std::cout << "READY\t1\n" << std::flush;
    std::string line;
    while (std::getline(std::cin, line)) {
      const size_t separator = line.find('\t');
      if (separator == std::string::npos || separator == 0) {
        std::cout << "0\tERROR\t"
                  << base64Encode("malformed request") << '\n' << std::flush;
        continue;
      }
      const std::string requestId = line.substr(0, separator);
      try {
        const std::string source = base64Decode(line.substr(separator + 1));
        const std::string result = translate(service, models, source);
        std::cout << requestId << "\tOK\t" << base64Encode(result)
                  << '\n' << std::flush;
      } catch (const std::exception& error) {
        std::cout << requestId << "\tERROR\t"
                  << base64Encode(error.what()) << '\n' << std::flush;
      }
    }
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
