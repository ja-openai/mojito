package mf2

type Error struct {
	Code    string
	Message string
}

func (e Error) Error() string {
	if e.Message == "" {
		return e.Code
	}
	return e.Code + ": " + e.Message
}

func mf2Error(code, message string) Error {
	return Error{Code: code, Message: message}
}

func missingArgument(name string) Error {
	return mf2Error("missing-argument", "Missing runtime argument: $"+name)
}

func badOperand(message string) Error {
	return mf2Error("bad-operand", message)
}

func badOption(message string) Error {
	return mf2Error("bad-option", message)
}

func badSelector(message string) Error {
	return mf2Error("bad-selector", message)
}

func unsupportedFunction(name string) Error {
	return mf2Error("unsupported-function", "Function :"+name+" is not supported by this formatter registry.")
}

func errorCode(err error) string {
	if err == nil {
		return ""
	}
	if mf2, ok := err.(Error); ok {
		return mf2.Code
	}
	return ""
}
