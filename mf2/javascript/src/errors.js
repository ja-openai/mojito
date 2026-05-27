export class MF2Error extends Error {
  constructor(code, message) {
    super(message);
    this.name = "MF2Error";
    this.code = code;
    this.message = message;
  }

  static missingArgument(name) {
    return new MF2Error("missing-argument", `Missing argument $${name}.`);
  }

  static badOperand(message) {
    return new MF2Error("bad-operand", message);
  }

  static badSelector(message) {
    return new MF2Error("bad-selector", message);
  }

  static badOption(message) {
    return new MF2Error("bad-option", message);
  }
}
