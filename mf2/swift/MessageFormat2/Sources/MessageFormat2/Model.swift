import Foundation

public enum MF2Message: Equatable {
    case message(declarations: [MF2Declaration], pattern: [MF2PatternPart])
    case select(declarations: [MF2Declaration], selectors: [MF2VariableRef], variants: [MF2Variant])
}

extension MF2Message: Decodable {
    private enum CodingKeys: String, CodingKey {
        case type
        case declarations
        case pattern
        case selectors
        case variants
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "message":
            self = .message(
                declarations: try container.decode([MF2Declaration].self, forKey: .declarations),
                pattern: try container.decode([MF2PatternPart].self, forKey: .pattern)
            )
        case "select":
            self = .select(
                declarations: try container.decode([MF2Declaration].self, forKey: .declarations),
                selectors: try container.decode([MF2VariableRef].self, forKey: .selectors),
                variants: try container.decode([MF2Variant].self, forKey: .variants)
            )
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unsupported MF2 message type: \(type)"
            )
        }
    }
}

public enum MF2Declaration: Equatable, Decodable {
    case input(name: String, value: MF2Expression)
    case local(name: String, value: MF2Expression)

    private enum CodingKeys: String, CodingKey {
        case type
        case name
        case value
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        let name = try container.decode(String.self, forKey: .name)
        let value = try container.decode(MF2Expression.self, forKey: .value)
        switch type {
        case "input":
            self = .input(name: name, value: value)
        case "local":
            self = .local(name: name, value: value)
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unsupported MF2 declaration type: \(type)"
            )
        }
    }
}

public enum MF2PatternPart: Equatable, Decodable {
    case text(String)
    case expression(MF2Expression)
    case markup(MF2Markup)

    public init(from decoder: Decoder) throws {
        if let text = try? String(from: decoder) {
            self = .text(text)
            return
        }

        let probe = try decoder.container(keyedBy: TypeCodingKey.self)
        let type = try probe.decode(String.self, forKey: .type)
        switch type {
        case "expression":
            self = .expression(try MF2Expression(from: decoder))
        case "markup":
            self = .markup(try MF2Markup(from: decoder))
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: probe,
                debugDescription: "Unsupported MF2 pattern part type: \(type)"
            )
        }
    }
}

public struct MF2Expression: Equatable, Decodable {
    public let arg: MF2ExpressionArgument?
    public let function: MF2Function?
    public let attributes: [String: MF2AttributeValue]

    private enum CodingKeys: String, CodingKey {
        case type
        case arg
        case function
        case attributes
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        _ = try container.decode(String.self, forKey: .type)
        arg = try container.decodeIfPresent(MF2ExpressionArgument.self, forKey: .arg)
        function = try container.decodeIfPresent(MF2Function.self, forKey: .function)
        attributes = try container.decodeIfPresent([String: MF2AttributeValue].self, forKey: .attributes) ?? [:]
    }
}

public enum MF2ExpressionArgument: Equatable, Decodable {
    case literal(String)
    case variable(String)

    private enum CodingKeys: String, CodingKey {
        case type
        case value
        case name
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "literal":
            self = .literal(try container.decode(String.self, forKey: .value))
        case "variable":
            self = .variable(try container.decode(String.self, forKey: .name))
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unsupported MF2 expression argument type: \(type)"
            )
        }
    }
}

public struct MF2VariableRef: Equatable, Decodable {
    public let name: String

    private enum CodingKeys: String, CodingKey {
        case type
        case name
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        _ = try container.decode(String.self, forKey: .type)
        name = try container.decode(String.self, forKey: .name)
    }
}

public struct MF2Function: Equatable, Decodable {
    public let name: String
    public let options: [String: MF2ExpressionArgument]

    private enum CodingKeys: String, CodingKey {
        case type
        case name
        case options
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        _ = try container.decode(String.self, forKey: .type)
        name = try container.decode(String.self, forKey: .name)
        options = try container.decodeIfPresent([String: MF2ExpressionArgument].self, forKey: .options) ?? [:]
    }
}

public struct MF2Markup: Equatable, Decodable {
    public let kind: String
    public let name: String
    public let options: [String: MF2ExpressionArgument]
    public let attributes: [String: MF2AttributeValue]

    private enum CodingKeys: String, CodingKey {
        case type
        case kind
        case name
        case options
        case attributes
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        _ = try container.decode(String.self, forKey: .type)
        kind = try container.decode(String.self, forKey: .kind)
        name = try container.decode(String.self, forKey: .name)
        options = try container.decodeIfPresent([String: MF2ExpressionArgument].self, forKey: .options) ?? [:]
        attributes = try container.decodeIfPresent([String: MF2AttributeValue].self, forKey: .attributes) ?? [:]
    }
}

public enum MF2AttributeValue: Equatable, Decodable {
    case literal(MF2ExpressionArgument)
    case present(Bool)

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let present = try? container.decode(Bool.self) {
            self = .present(present)
            return
        }
        self = .literal(try MF2ExpressionArgument(from: decoder))
    }
}

public struct MF2Variant: Equatable, Decodable {
    public let keys: [MF2VariantKey]
    public let value: [MF2PatternPart]
}

public enum MF2VariantKey: Hashable, Decodable {
    case literal(String)
    case catchAll

    private enum CodingKeys: String, CodingKey {
        case type
        case value
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "literal":
            self = .literal(try container.decode(String.self, forKey: .value))
        case "*":
            self = .catchAll
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unsupported MF2 variant key type: \(type)"
            )
        }
    }
}

public enum MF2Value: Equatable, Decodable {
    case string(String)
    case number(String)
    case bool(Bool)
    case null

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode(Int.self) {
            self = .number(String(value))
        } else if let value = try? container.decode(Double.self) {
            self = .number(Self.format(double: value))
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported MF2 argument value."
            )
        }
    }

    var rendered: String {
        switch self {
        case let .string(value), let .number(value):
            value
        case let .bool(value):
            String(value)
        case .null:
            ""
        }
    }

    private static func format(double: Double) -> String {
        if double.rounded() == double {
            String(Int(double))
        } else {
            String(double)
        }
    }
}

struct TypeCodingKey: CodingKey {
    var stringValue: String
    var intValue: Int?

    init?(stringValue: String) {
        self.stringValue = stringValue
    }

    init?(intValue: Int) {
        self.stringValue = String(intValue)
        self.intValue = intValue
    }

    static let type = TypeCodingKey(stringValue: "type")!
}
