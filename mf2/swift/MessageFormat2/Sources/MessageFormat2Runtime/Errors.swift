import Foundation

public enum MF2Error: Error, Equatable, CustomStringConvertible {
    case missingArgument(String)
    case missingSelectVariant
    case unsupportedFunction(String)
    case unsupportedExpression
    case duplicateDeclaration(String)
    case variantKeyCountMismatch
    case duplicateVariant
    case missingFallbackVariant
    case missingSelectorAnnotation(String)
    case invalidInputDeclaration(String)

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
        case .duplicateDeclaration:
            "duplicate-declaration"
        case .variantKeyCountMismatch:
            "variant-key-count-mismatch"
        case .duplicateVariant:
            "duplicate-variant"
        case .missingFallbackVariant:
            "missing-fallback-variant"
        case .missingSelectorAnnotation:
            "missing-selector-annotation"
        case .invalidInputDeclaration:
            "invalid-input-declaration"
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
        case let .duplicateDeclaration(name):
            "Declaration $\(name) is defined more than once."
        case .variantKeyCountMismatch:
            "Variant key count must match selector count."
        case .duplicateVariant:
            "Select variants must have unique key tuples."
        case .missingFallbackVariant:
            "Select messages must include a catch-all fallback variant."
        case let .missingSelectorAnnotation(name):
            "Selector $\(name) must reference a declaration with a function."
        case let .invalidInputDeclaration(name):
            "Input declaration $\(name) must bind the same variable name."
        }
    }
}
