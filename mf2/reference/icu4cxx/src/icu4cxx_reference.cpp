#include <unicode/messageformat2.h>
#include <unicode/messageformat2_arguments.h>
#include <unicode/messageformat2_formattable.h>
#include <unicode/parseerr.h>
#include <unicode/stringpiece.h>
#include <unicode/uloc.h>
#include <unicode/utypes.h>

#include <chrono>
#include <cstdint>
#include <iostream>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "generated_cases.hpp"

using icu::Locale;
using icu::StringPiece;
using icu::UnicodeString;
using icu::message2::Formattable;
using icu::message2::MessageArguments;
using icu::message2::MessageFormatter;

namespace {

volatile int sink = 0;

struct SupportedCase {
    std::string name;
    std::string expected;
    MessageFormatter formatter;
    MessageArguments arguments;

    SupportedCase(
        std::string caseName,
        std::string caseExpected,
        const MessageFormatter::Builder& builder,
        const std::map<UnicodeString, Formattable>& argMap,
        UErrorCode& status)
        : name(std::move(caseName)),
          expected(std::move(caseExpected)),
          formatter(builder.build(status)),
          arguments(argMap, status) {}
};

struct PreparedCase {
    std::string name;
    std::string expected;
    std::string unsupportedReason;
    std::unique_ptr<SupportedCase> supported;
};

UnicodeString fromUtf8(const std::string& value) {
    return UnicodeString::fromUTF8(StringPiece(value.data(), static_cast<int32_t>(value.size())));
}

std::map<UnicodeString, Formattable> buildArgumentMap(const GeneratedCase& generated) {
    std::map<UnicodeString, Formattable> args;
    for (const GeneratedArg& arg : generated.arguments) {
        UnicodeString name = fromUtf8(arg.name);
        switch (arg.type) {
            case GeneratedArgType::String:
                args.emplace(name, Formattable(fromUtf8(arg.stringValue)));
                break;
            case GeneratedArgType::Int:
                args.emplace(name, Formattable(static_cast<int64_t>(arg.intValue)));
                break;
            case GeneratedArgType::Double:
                args.emplace(name, Formattable(arg.doubleValue));
                break;
            case GeneratedArgType::Bool:
                args.emplace(name, Formattable(arg.boolValue ? int64_t{1} : int64_t{0}));
                break;
        }
    }
    return args;
}

std::string toUtf8(const UnicodeString& value) {
    std::string output;
    value.toUTF8String(output);
    return output;
}

std::string errorName(UErrorCode status) {
    return U_SUCCESS(status) ? std::string() : std::string(u_errorName(status));
}

PreparedCase prepareCase(const GeneratedCase& generated) {
    PreparedCase prepared;
    prepared.name = generated.name;
    prepared.expected = generated.expected;

    UErrorCode status = U_ZERO_ERROR;
    UParseError parseError;
    MessageFormatter::Builder builder(status);
    if (U_FAILURE(status)) {
        prepared.unsupportedReason = errorName(status);
        return prepared;
    }
    if (generated.bidiIsolation != "none") {
        prepared.unsupportedReason = "bidi isolation option not wired in ICU4C++ harness";
        return prepared;
    }

    builder.setLocale(Locale::createFromName(generated.locale.c_str()))
           .setErrorHandlingBehavior(MessageFormatter::U_MF_STRICT)
           .setPattern(fromUtf8(generated.source), parseError, status);
    if (U_FAILURE(status)) {
        prepared.unsupportedReason = errorName(status);
        return prepared;
    }

    auto argMap = buildArgumentMap(generated);
    auto supported =
        std::make_unique<SupportedCase>(generated.name, generated.expected, builder, argMap, status);
    if (U_FAILURE(status)) {
        prepared.unsupportedReason = errorName(status);
        return prepared;
    }

    UnicodeString preflight = supported->formatter.formatToString(supported->arguments, status);
    (void) preflight;
    if (U_FAILURE(status)) {
        prepared.unsupportedReason = errorName(status);
        return prepared;
    }

    prepared.supported = std::move(supported);
    return prepared;
}

std::vector<PreparedCase> prepareCases() {
    std::vector<PreparedCase> prepared;
    for (const GeneratedCase& generated : generatedCases()) {
        prepared.push_back(prepareCase(generated));
    }
    return prepared;
}

int compare() {
    std::vector<PreparedCase> cases = prepareCases();
    int passed = 0;
    int failed = 0;
    int unsupported = 0;

    for (PreparedCase& prepared : cases) {
        if (!prepared.unsupportedReason.empty()) {
            unsupported++;
            std::cout << "UNSUPPORTED " << prepared.name << ": " << prepared.unsupportedReason
                      << "\n";
            continue;
        }

        UErrorCode status = U_ZERO_ERROR;
        std::string actual =
            toUtf8(prepared.supported->formatter.formatToString(prepared.supported->arguments, status));
        if (U_FAILURE(status)) {
            unsupported++;
            std::cout << "UNSUPPORTED " << prepared.name << ": " << errorName(status) << "\n";
        } else if (actual == prepared.expected) {
            passed++;
        } else {
            failed++;
            std::cout << "MISMATCH " << prepared.name << ":\n  expected: " << prepared.expected
                      << "\n  actual:   " << actual << "\n";
        }
    }

    std::cout << "icu4cxx compare total=" << cases.size() << " passed=" << passed
              << " failed=" << failed << " unsupported=" << unsupported << "\n";
    return failed == 0 ? 0 : 1;
}

int bench(int iterations, int warmupIterations) {
    std::vector<PreparedCase> allCases = prepareCases();
    std::vector<PreparedCase*> cases;
    for (PreparedCase& prepared : allCases) {
        if (prepared.unsupportedReason.empty()) {
            cases.push_back(&prepared);
        }
    }
    if (cases.empty()) {
        std::cerr << "No supported ICU4C++ format cases found.\n";
        return 2;
    }

    for (int index = 0; index < warmupIterations; index++) {
        PreparedCase* prepared = cases[static_cast<size_t>(index) % cases.size()];
        UErrorCode status = U_ZERO_ERROR;
        UnicodeString output =
            prepared->supported->formatter.formatToString(prepared->supported->arguments, status);
        if (U_FAILURE(status)) {
            std::cerr << "Warmup failed for " << prepared->name << ": " << errorName(status) << "\n";
            return 1;
        }
        sink = output.length();
    }

    int64_t bytes = 0;
    auto started = std::chrono::steady_clock::now();
    for (int index = 0; index < iterations; index++) {
        PreparedCase* prepared = cases[static_cast<size_t>(index) % cases.size()];
        UErrorCode status = U_ZERO_ERROR;
        std::string output =
            toUtf8(prepared->supported->formatter.formatToString(prepared->supported->arguments, status));
        if (U_FAILURE(status)) {
            std::cerr << "Format failed for " << prepared->name << ": " << errorName(status) << "\n";
            return 1;
        }
        bytes += static_cast<int64_t>(output.size());
    }
    auto elapsed = std::chrono::steady_clock::now() - started;
    double seconds = std::chrono::duration<double>(elapsed).count();
    sink = static_cast<int>(bytes);

    std::cout << "icu4cxx format iterations=" << iterations << " warmup=" << warmupIterations
              << " cases=" << cases.size() << " seconds=" << seconds
              << " ops_per_second=" << static_cast<int64_t>(iterations / seconds)
              << " bytes=" << bytes << "\n";
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    std::string command = argc > 1 ? argv[1] : "compare";
    if (command == "compare") {
        return compare();
    }
    if (command == "bench") {
        int iterations = argc > 2 ? std::stoi(argv[2]) : 100000;
        int warmupIterations = argc > 3 ? std::stoi(argv[3]) : 10000;
        return bench(iterations, warmupIterations);
    }

    std::cerr << "Usage: icu4cxx-reference [compare|bench] [iterations] [warmup-iterations]\n";
    return 2;
}
