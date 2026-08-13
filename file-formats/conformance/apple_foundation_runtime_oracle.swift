import Foundation

struct RuntimeSample: Decodable {
  let bundle: String
  let fixture: String
  let message: String
  let arguments: [Argument]
  let expected: String
  let fallback: Bool?
  let presentationWidth: Int?
  let locale: String?
}

func fail(_ message: String) -> Never {
  FileHandle.standardError.write(Data((message + "\n").utf8))
  exit(1)
}

enum Argument: Decodable {
  case string(String)
  case integer(Int64)
  case number(Double)

  init(from decoder: Decoder) throws {
    let container = try decoder.singleValueContainer()
    if let value = try? container.decode(String.self) {
      self = .string(value)
    } else if let value = try? container.decode(Int64.self) {
      self = .integer(value)
    } else {
      self = .number(try container.decode(Double.self))
    }
  }

  var value: CVarArg {
    switch self {
    case .string(let value): value as NSString
    case .integer(let value): value
    case .number(let value): value
    }
  }
}

let source = URL(fileURLWithPath: CommandLine.arguments[1])
let samples = try JSONDecoder().decode([RuntimeSample].self, from: Data(contentsOf: source))
for sample in samples {
  guard let bundle = Bundle(path: sample.bundle) else {
    fail("\(sample.fixture): Foundation could not open \(sample.bundle)")
  }
  let defaultValue = sample.fallback == true ? "__MOJITO_FOUNDATION_FALLBACK__" : nil
  let localized = bundle.localizedString(
    forKey: sample.message, value: defaultValue, table: "Localizable"
  )
  let format =
    if let width = sample.presentationWidth {
      (localized as NSString).variantFittingPresentationWidth(width)
    } else {
      localized
    }
  let actual = String(
    format: format,
    locale: Locale(identifier: sample.locale ?? "en"),
    arguments: sample.arguments.map(\.value)
  )
  if actual != sample.expected {
    fail(
      "\(sample.fixture)/\(sample.message): Foundation returned \(actual.debugDescription), "
        + "expected \(sample.expected.debugDescription)"
    )
  }
}
print("Foundation rendered \(samples.count) native localized-string selections.")
