export class MF2Error extends Error {
  code: string;
  message: string;

  constructor(code: string, message: string);

  static missingArgument(name: string): MF2Error;
  static badOperand(message: string): MF2Error;
  static badSelector(message: string): MF2Error;
  static badOption(message: string): MF2Error;
}
