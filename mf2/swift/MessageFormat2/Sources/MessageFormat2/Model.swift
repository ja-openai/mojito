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
    public init(
        arg: MF2ExpressionArgument? = nil, function: MF2Function? = nil, attributes: [String: MF2AttributeValue] = [:]
    ) { self.arg = arg; self.function = function; self.attributes = attributes }

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
        guard try container.decode(String.self, forKey: .type) == "expression" else {
            throw DecodingError.dataCorruptedError(
                forKey: .type, in: container, debugDescription: "Expected expression type.")
        }
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
    public init(name: String) { self.name = name }

    public let name: String

    private enum CodingKeys: String, CodingKey {
        case type
        case name
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        guard try container.decode(String.self, forKey: .type) == "variable" else {
            throw DecodingError.dataCorruptedError(
                forKey: .type, in: container, debugDescription: "Expected variable type.")
        }
        name = try container.decode(String.self, forKey: .name)
    }
}

public struct MF2Function: Equatable, Decodable {
    public init(name: String, options: [String: MF2ExpressionArgument] = [:]) {
        self.name = name; self.options = options
    }

    public let name: String
    public let options: [String: MF2ExpressionArgument]

    private enum CodingKeys: String, CodingKey {
        case type
        case name
        case options
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        guard try container.decode(String.self, forKey: .type) == "function" else {
            throw DecodingError.dataCorruptedError(
                forKey: .type, in: container, debugDescription: "Expected function type.")
        }
        name = try container.decode(String.self, forKey: .name)
        options = try container.decodeIfPresent([String: MF2ExpressionArgument].self, forKey: .options) ?? [:]
    }
}

public struct MF2Markup: Equatable, Decodable {
    public init(
        kind: String, name: String, options: [String: MF2ExpressionArgument] = [:],
        attributes: [String: MF2AttributeValue] = [:]
    ) { self.kind = kind; self.name = name; self.options = options; self.attributes = attributes }

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
        guard try container.decode(String.self, forKey: .type) == "markup" else {
            throw DecodingError.dataCorruptedError(
                forKey: .type, in: container, debugDescription: "Expected markup type.")
        }
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
    public init(keys: [MF2VariantKey], value: [MF2PatternPart]) { self.keys = keys; self.value = value }

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
        } else if let value = try? container.decode(UInt64.self) {
            self = .number(String(value))
        } else if let value = try? container.decode(Double.self) {
            guard value.isFinite, abs(value) <= 9_007_199_254_740_991 else {
                throw DecodingError.dataCorruptedError(
                    in: container,
                    debugDescription:
                        "Numeric value exceeds the exact native decoding range; supply an exact decimal string instead."
                )
            }
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
        if let integer = Int(exactly: double) {
            String(integer)
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

// The supported semantic model round-trips through Codable. Unknown extension
// properties are ignored when decoding and are not retained when encoding.
private enum ModelKey: String, CodingKey {
    case type, declarations, pattern, selectors, variants, name, value, arg, function, attributes, options, kind, keys
}
extension MF2Message: Encodable {
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: ModelKey.self)
        switch self {
        case let .message(declarations, pattern):
            try c.encode("message", forKey: .type); try c.encode(declarations, forKey: .declarations);
            try c.encode(pattern, forKey: .pattern)
        case let .select(declarations, selectors, variants):
            try c.encode("select", forKey: .type); try c.encode(declarations, forKey: .declarations);
            try c.encode(selectors, forKey: .selectors); try c.encode(variants, forKey: .variants)
        }
    }
}
extension MF2Declaration: Encodable {
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: ModelKey.self)
        switch self {
        case let .input(name, value):
            try c.encode("input", forKey: .type); try c.encode(name, forKey: .name); try c.encode(value, forKey: .value)
        case let .local(name, value):
            try c.encode("local", forKey: .type); try c.encode(name, forKey: .name); try c.encode(value, forKey: .value)
        }
    }
}
extension MF2PatternPart: Encodable {
    public func encode(to encoder: Encoder) throws {
        switch self {
        case let .text(value): var c = encoder.singleValueContainer(); try c.encode(value)
        case let .expression(value): try value.encode(to: encoder)
        case let .markup(value): try value.encode(to: encoder)
        }
    }
}
extension MF2Expression: Encodable {
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: ModelKey.self)
        try c.encode("expression", forKey: .type); try c.encodeIfPresent(arg, forKey: .arg);
        try c.encodeIfPresent(function, forKey: .function)
        if !attributes.isEmpty { try c.encode(attributes, forKey: .attributes) }
    }
}
extension MF2ExpressionArgument: Encodable {
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: ModelKey.self)
        switch self {
        case let .literal(value): try c.encode("literal", forKey: .type); try c.encode(value, forKey: .value)
        case let .variable(name): try c.encode("variable", forKey: .type); try c.encode(name, forKey: .name)
        }
    }
}
extension MF2VariableRef: Encodable {
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: ModelKey.self)
        try c.encode("variable", forKey: .type); try c.encode(name, forKey: .name)
    }
}
extension MF2Function: Encodable {
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: ModelKey.self)
        try c.encode("function", forKey: .type); try c.encode(name, forKey: .name)
        if !options.isEmpty { try c.encode(options, forKey: .options) }
    }
}
extension MF2Markup: Encodable {
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: ModelKey.self)
        try c.encode("markup", forKey: .type); try c.encode(kind, forKey: .kind); try c.encode(name, forKey: .name)
        if !options.isEmpty { try c.encode(options, forKey: .options) }
        if !attributes.isEmpty { try c.encode(attributes, forKey: .attributes) }
    }
}
extension MF2AttributeValue: Encodable {
    public func encode(to encoder: Encoder) throws {
        switch self {
        case let .present(value): var c = encoder.singleValueContainer(); try c.encode(value)
        case let .literal(value): try value.encode(to: encoder)
        }
    }
}
extension MF2Variant: Encodable {}
extension MF2VariantKey: Encodable {
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: ModelKey.self)
        switch self {
        case let .literal(value): try c.encode("literal", forKey: .type); try c.encode(value, forKey: .value)
        case .catchAll: try c.encode("*", forKey: .type)
        }
    }
}