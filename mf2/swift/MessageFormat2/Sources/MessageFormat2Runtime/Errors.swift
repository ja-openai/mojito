import Foundation

public enum MF2Error: Error, Equatable, CustomStringConvertible {
    case missingArgument(String)
    case missingSelectVariant
    case unsupportedFunction(String)
    case unsupportedExpression

    public var code: String {
        switch self {
        case .missingArgument:
            "missing-argument"
        case .missingSelectVariant:
            "missing-select-variant"
        case .unsupportedFunction:
            "unsupported-function"
        case .unsupportedExpression:
            "unsupported-expression"
        }
    }

    public var description: String {
        switch self {
        case let .missingArgument(name):
            "Missing argument $\(name)."
        case .missingSelectVariant:
            "No select variant matched and no catch-all variant is present."
        case let .unsupportedFunction(name):
            "Function :\(name) is not supported by this runtime slice."
        case .unsupportedExpression:
            "Expression shape is not supported by this runtime slice."
        }
    }
}
