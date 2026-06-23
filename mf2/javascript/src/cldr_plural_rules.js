// Generated from Unicode CLDR by mf2/cldr/update_generated.sh; do not edit by hand.
import { pluralLookupChain } from "./locale-key.js";

const MAX_PLURAL_OPERAND_LENGTH = 256;
const MAX_SAFE_PLURAL_INTEGER = Number.MAX_SAFE_INTEGER;

export class NumberOperands {
  constructor(value) {
    if (typeof value === "number" && Number.isFinite(value) && Number.isInteger(value)) {
      if (!Number.isSafeInteger(value)) throw new RangeError(`Unsupported plural operand value: ${value}`);
      this.n = Math.abs(value);
      this.i = Math.trunc(this.n);
      this.v = 0;
      this.w = 0;
      this.f = 0;
      this.t = 0;
      this.e = 0;
      this.c = 0;
      return;
    }
    const raw = String(value).trim();
    if (raw.length > MAX_PLURAL_OPERAND_LENGTH) {
      throw new RangeError("Unsupported plural operand value");
    }
    if (!/^[+-]?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(raw)) {
      throw new RangeError(`Unsupported plural operand value: ${value}`);
    }
    const n = Math.abs(Number(raw));
    if (!Number.isFinite(n) || n > MAX_SAFE_PLURAL_INTEGER) throw new RangeError(`Unsupported plural operand value: ${value}`);
    const normalized = raw.replace(/^[+-]+/, "").toLowerCase();
    const base = normalized.split("e", 1)[0];
    const fraction = base.includes(".") ? base.split(".", 2)[1] : "";
    const trimmedFraction = fraction.replace(/0+$/, "");
    this.n = n;
    this.i = Math.trunc(n);
    this.v = fraction.length;
    this.w = trimmedFraction.length;
    this.f = parseSafePluralInteger(fraction);
    this.t = parseSafePluralInteger(trimmedFraction);
    this.e = 0;
    this.c = 0;
  }

  operand(name) {
    return this[name] ?? 0;
  }
}

function parseSafePluralInteger(text) {
  if (text === "") return 0;
  const normalized = text.replace(/^0+/, "") || "0";
  const value = Number(normalized);
  if (!Number.isSafeInteger(value)) throw new RangeError("Unsupported plural operand value");
  return value;
}

export function selectCardinal(locale, value) {
  const operands = value instanceof NumberOperands ? value : new NumberOperands(value);
  switch (lookupRuleId(CARDINAL_LOCALES, CARDINAL_PARENTS, locale)) {
    case "r0": return selectCardinalR0(operands);
    case "r1": return selectCardinalR1(operands);
    case "r2": return selectCardinalR2(operands);
    case "r3": return selectCardinalR3(operands);
    case "r4": return selectCardinalR4(operands);
    case "r5": return selectCardinalR5(operands);
    case "r6": return selectCardinalR6(operands);
    case "r7": return selectCardinalR7(operands);
    case "r8": return selectCardinalR8(operands);
    case "r9": return selectCardinalR9(operands);
    case "r10": return selectCardinalR10(operands);
    case "r11": return selectCardinalR11(operands);
    case "r12": return selectCardinalR12(operands);
    case "r13": return selectCardinalR13(operands);
    case "r14": return selectCardinalR14(operands);
    case "r15": return selectCardinalR15(operands);
    case "r16": return selectCardinalR16(operands);
    case "r17": return selectCardinalR17(operands);
    case "r18": return selectCardinalR18(operands);
    case "r19": return selectCardinalR19(operands);
    case "r20": return selectCardinalR20(operands);
    case "r21": return selectCardinalR21(operands);
    case "r22": return selectCardinalR22(operands);
    case "r23": return selectCardinalR23(operands);
    case "r24": return selectCardinalR24(operands);
    case "r25": return selectCardinalR25(operands);
    case "r26": return selectCardinalR26(operands);
    case "r27": return selectCardinalR27(operands);
    case "r28": return selectCardinalR28(operands);
    case "r29": return selectCardinalR29(operands);
    case "r30": return selectCardinalR30(operands);
    case "r31": return selectCardinalR31(operands);
    case "r32": return selectCardinalR32(operands);
    case "r33": return selectCardinalR33(operands);
    case "r34": return selectCardinalR34(operands);
    case "r35": return selectCardinalR35(operands);
    case "r36": return selectCardinalR36(operands);
    case "r37": return selectCardinalR37(operands);
    case "r38": return selectCardinalR38(operands);
    case "r39": return selectCardinalR39(operands);
    default: return "other";
  }
}

export function selectOrdinal(locale, value) {
  const operands = value instanceof NumberOperands ? value : new NumberOperands(value);
  switch (lookupRuleId(ORDINAL_LOCALES, ORDINAL_PARENTS, locale)) {
    case "r0": return selectOrdinalR0(operands);
    case "r1": return selectOrdinalR1(operands);
    case "r2": return selectOrdinalR2(operands);
    case "r3": return selectOrdinalR3(operands);
    case "r4": return selectOrdinalR4(operands);
    case "r5": return selectOrdinalR5(operands);
    case "r6": return selectOrdinalR6(operands);
    case "r7": return selectOrdinalR7(operands);
    case "r8": return selectOrdinalR8(operands);
    case "r9": return selectOrdinalR9(operands);
    case "r10": return selectOrdinalR10(operands);
    case "r11": return selectOrdinalR11(operands);
    case "r12": return selectOrdinalR12(operands);
    case "r13": return selectOrdinalR13(operands);
    case "r14": return selectOrdinalR14(operands);
    case "r15": return selectOrdinalR15(operands);
    case "r16": return selectOrdinalR16(operands);
    case "r17": return selectOrdinalR17(operands);
    case "r18": return selectOrdinalR18(operands);
    case "r19": return selectOrdinalR19(operands);
    case "r20": return selectOrdinalR20(operands);
    case "r21": return selectOrdinalR21(operands);
    case "r22": return selectOrdinalR22(operands);
    case "r23": return selectOrdinalR23(operands);
    case "r24": return selectOrdinalR24(operands);
    default: return "other";
  }
}

const CARDINAL_LOCALES = {
  "af": "r0",
  "ak": "r1",
  "am": "r2",
  "an": "r0",
  "ar": "r3",
  "ars": "r3",
  "as": "r2",
  "asa": "r0",
  "ast": "r4",
  "az": "r0",
  "bal": "r0",
  "be": "r5",
  "bem": "r0",
  "bez": "r0",
  "bg": "r0",
  "bho": "r1",
  "blo": "r6",
  "bm": "r7",
  "bn": "r2",
  "bo": "r7",
  "br": "r8",
  "brx": "r0",
  "bs": "r9",
  "ca": "r10",
  "ce": "r0",
  "ceb": "r11",
  "cgg": "r0",
  "chr": "r0",
  "ckb": "r0",
  "cs": "r12",
  "csw": "r1",
  "cv": "r6",
  "cy": "r13",
  "da": "r14",
  "de": "r4",
  "doi": "r2",
  "dsb": "r15",
  "dv": "r0",
  "dz": "r7",
  "ee": "r0",
  "el": "r0",
  "en": "r4",
  "eo": "r0",
  "es": "r16",
  "et": "r4",
  "eu": "r0",
  "fa": "r2",
  "ff": "r17",
  "fi": "r4",
  "fil": "r11",
  "fo": "r0",
  "fr": "r18",
  "fur": "r0",
  "fy": "r4",
  "ga": "r19",
  "gd": "r20",
  "gl": "r4",
  "gsw": "r0",
  "gu": "r2",
  "guw": "r1",
  "gv": "r21",
  "ha": "r0",
  "haw": "r0",
  "he": "r22",
  "hi": "r2",
  "hnj": "r7",
  "hr": "r9",
  "hsb": "r15",
  "hu": "r0",
  "hy": "r17",
  "ia": "r4",
  "id": "r7",
  "ie": "r4",
  "ig": "r7",
  "ii": "r7",
  "io": "r4",
  "is": "r23",
  "it": "r10",
  "iu": "r24",
  "ja": "r7",
  "jbo": "r7",
  "jgo": "r0",
  "jmc": "r0",
  "jv": "r7",
  "jw": "r7",
  "ka": "r0",
  "kab": "r17",
  "kaj": "r0",
  "kcg": "r0",
  "kde": "r7",
  "kea": "r7",
  "kk": "r0",
  "kkj": "r0",
  "kl": "r0",
  "km": "r7",
  "kn": "r2",
  "ko": "r7",
  "kok": "r2",
  "kok-Latn": "r2",
  "ks": "r0",
  "ksb": "r0",
  "ksh": "r6",
  "ku": "r0",
  "kw": "r25",
  "ky": "r0",
  "lag": "r26",
  "lb": "r0",
  "lg": "r0",
  "lij": "r4",
  "lkt": "r7",
  "lld": "r10",
  "ln": "r1",
  "lo": "r7",
  "lt": "r27",
  "lv": "r28",
  "mas": "r0",
  "mg": "r1",
  "mgo": "r0",
  "mk": "r29",
  "ml": "r0",
  "mn": "r0",
  "mo": "r30",
  "mr": "r0",
  "ms": "r7",
  "mt": "r31",
  "my": "r7",
  "nah": "r0",
  "naq": "r24",
  "nb": "r0",
  "nd": "r0",
  "ne": "r0",
  "nl": "r4",
  "nn": "r0",
  "nnh": "r0",
  "no": "r0",
  "nqo": "r7",
  "nr": "r0",
  "nso": "r1",
  "ny": "r0",
  "nyn": "r0",
  "om": "r0",
  "or": "r0",
  "os": "r0",
  "osa": "r7",
  "pa": "r1",
  "pap": "r0",
  "pcm": "r2",
  "pl": "r32",
  "prg": "r28",
  "ps": "r0",
  "pt": "r33",
  "pt-PT": "r10",
  "rm": "r0",
  "ro": "r30",
  "rof": "r0",
  "ru": "r34",
  "rwk": "r0",
  "sah": "r7",
  "saq": "r0",
  "sat": "r24",
  "sc": "r4",
  "scn": "r10",
  "sd": "r0",
  "sdh": "r0",
  "se": "r24",
  "seh": "r0",
  "ses": "r7",
  "sg": "r7",
  "sgs": "r35",
  "sh": "r9",
  "shi": "r36",
  "si": "r37",
  "sk": "r12",
  "sl": "r38",
  "sma": "r24",
  "smi": "r24",
  "smj": "r24",
  "smn": "r24",
  "sms": "r24",
  "sn": "r0",
  "so": "r0",
  "sq": "r0",
  "sr": "r9",
  "ss": "r0",
  "ssy": "r0",
  "st": "r0",
  "su": "r7",
  "sv": "r4",
  "sw": "r4",
  "syr": "r0",
  "ta": "r0",
  "te": "r0",
  "teo": "r0",
  "th": "r7",
  "ti": "r1",
  "tig": "r0",
  "tk": "r0",
  "tl": "r11",
  "tn": "r0",
  "to": "r7",
  "tpi": "r7",
  "tr": "r0",
  "ts": "r0",
  "tzm": "r39",
  "ug": "r0",
  "uk": "r34",
  "und": "r7",
  "ur": "r4",
  "uz": "r0",
  "ve": "r0",
  "vec": "r10",
  "vi": "r7",
  "vo": "r0",
  "vun": "r0",
  "wa": "r1",
  "wae": "r0",
  "wo": "r7",
  "xh": "r0",
  "xog": "r0",
  "yi": "r4",
  "yo": "r7",
  "yue": "r7",
  "zh": "r7",
  "zu": "r2",
};

const ORDINAL_LOCALES = {
  "af": "r0",
  "am": "r0",
  "an": "r0",
  "ar": "r0",
  "as": "r1",
  "ast": "r0",
  "az": "r2",
  "bal": "r3",
  "be": "r4",
  "bg": "r0",
  "blo": "r5",
  "bn": "r1",
  "bs": "r0",
  "ca": "r6",
  "ce": "r0",
  "cs": "r0",
  "cv": "r0",
  "cy": "r7",
  "da": "r0",
  "de": "r0",
  "dsb": "r0",
  "el": "r0",
  "en": "r8",
  "es": "r0",
  "et": "r0",
  "eu": "r0",
  "fa": "r0",
  "fi": "r0",
  "fil": "r3",
  "fr": "r3",
  "fy": "r0",
  "ga": "r3",
  "gd": "r9",
  "gl": "r0",
  "gsw": "r0",
  "gu": "r10",
  "he": "r0",
  "hi": "r10",
  "hr": "r0",
  "hsb": "r0",
  "hu": "r11",
  "hy": "r3",
  "ia": "r0",
  "id": "r0",
  "ie": "r0",
  "is": "r0",
  "it": "r12",
  "ja": "r0",
  "ka": "r13",
  "kk": "r14",
  "km": "r0",
  "kn": "r0",
  "ko": "r0",
  "kok": "r15",
  "kok-Latn": "r15",
  "kw": "r16",
  "ky": "r0",
  "lij": "r17",
  "lld": "r12",
  "lo": "r3",
  "lt": "r0",
  "lv": "r0",
  "mk": "r18",
  "ml": "r0",
  "mn": "r0",
  "mo": "r3",
  "mr": "r15",
  "ms": "r3",
  "my": "r0",
  "nb": "r0",
  "ne": "r19",
  "nl": "r0",
  "no": "r0",
  "or": "r20",
  "pa": "r0",
  "pl": "r0",
  "prg": "r0",
  "ps": "r0",
  "pt": "r0",
  "ro": "r3",
  "ru": "r0",
  "sc": "r12",
  "scn": "r17",
  "sd": "r0",
  "sh": "r0",
  "si": "r0",
  "sk": "r0",
  "sl": "r0",
  "sq": "r21",
  "sr": "r0",
  "sv": "r22",
  "sw": "r0",
  "ta": "r0",
  "te": "r0",
  "th": "r0",
  "tk": "r23",
  "tl": "r3",
  "tpi": "r0",
  "tr": "r0",
  "uk": "r24",
  "und": "r0",
  "ur": "r0",
  "uz": "r0",
  "vec": "r12",
  "vi": "r3",
  "yue": "r0",
  "zh": "r0",
  "zu": "r0",
};

const CARDINAL_PARENTS = {
};

const ORDINAL_PARENTS = {
};

function lookupRuleId(locales, parents, locale) {
  for (const candidate of pluralLookupChain(locale, parents)) {
    const rule = locales[candidate];
    if (rule != null) return rule;
  }
  return null;
}

function selectCardinalR0(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  return "other";
}

function selectCardinalR1(operands) {
  if (Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  return "other";
}

function selectCardinalR2(operands) {
  if ((0 <= operands.operand("i") && operands.operand("i") <= 0) || (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1)) return "one";
  return "other";
}

function selectCardinalR3(operands) {
  if (Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) return "zero";
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if (Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) return "two";
  if (Number.isInteger((operands.operand("n") % 100)) && 3 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 10) return "few";
  if (Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 99) return "many";
  return "other";
}

function selectCardinalR4(operands) {
  if (1 <= operands.operand("i") && operands.operand("i") <= 1 && 0 <= operands.operand("v") && operands.operand("v") <= 0) return "one";
  return "other";
}

function selectCardinalR5(operands) {
  if (Number.isInteger((operands.operand("n") % 10)) && 1 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 1 && !(Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 11)) return "one";
  if (Number.isInteger((operands.operand("n") % 10)) && 2 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 4 && !(Number.isInteger((operands.operand("n") % 100)) && 12 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 14)) return "few";
  if ((Number.isInteger((operands.operand("n") % 10)) && 0 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 0) || (Number.isInteger((operands.operand("n") % 10)) && 5 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 9) || (Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 14)) return "many";
  return "other";
}

function selectCardinalR6(operands) {
  if (Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) return "zero";
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  return "other";
}

function selectCardinalR7(_operands) {
  return "other";
}

function selectCardinalR8(operands) {
  if (Number.isInteger((operands.operand("n") % 10)) && 1 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 1 && !(((Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 11) || (Number.isInteger((operands.operand("n") % 100)) && 71 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 71) || (Number.isInteger((operands.operand("n") % 100)) && 91 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 91)))) return "one";
  if (Number.isInteger((operands.operand("n") % 10)) && 2 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 2 && !(((Number.isInteger((operands.operand("n") % 100)) && 12 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 12) || (Number.isInteger((operands.operand("n") % 100)) && 72 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 72) || (Number.isInteger((operands.operand("n") % 100)) && 92 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 92)))) return "two";
  if (((Number.isInteger((operands.operand("n") % 10)) && 3 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 4) || (Number.isInteger((operands.operand("n") % 10)) && 9 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 9)) && !(((Number.isInteger((operands.operand("n") % 100)) && 10 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 19) || (Number.isInteger((operands.operand("n") % 100)) && 70 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 79) || (Number.isInteger((operands.operand("n") % 100)) && 90 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 99)))) return "few";
  if (!(Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) && Number.isInteger((operands.operand("n") % 1000000)) && 0 <= (operands.operand("n") % 1000000) && (operands.operand("n") % 1000000) <= 0) return "many";
  return "other";
}

function selectCardinalR9(operands) {
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && 1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1 && !(11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11)) || (1 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 1 && !(11 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 11))) return "one";
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && 2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4 && !(12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14)) || (2 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 4 && !(12 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 14))) return "few";
  return "other";
}

function selectCardinalR10(operands) {
  if (1 <= operands.operand("i") && operands.operand("i") <= 1 && 0 <= operands.operand("v") && operands.operand("v") <= 0) return "one";
  if ((0 <= operands.operand("e") && operands.operand("e") <= 0 && !(0 <= operands.operand("i") && operands.operand("i") <= 0) && 0 <= (operands.operand("i") % 1000000) && (operands.operand("i") % 1000000) <= 0 && 0 <= operands.operand("v") && operands.operand("v") <= 0) || (!(0 <= operands.operand("e") && operands.operand("e") <= 5))) return "many";
  return "other";
}

function selectCardinalR11(operands) {
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && ((1 <= operands.operand("i") && operands.operand("i") <= 1) || (2 <= operands.operand("i") && operands.operand("i") <= 2) || (3 <= operands.operand("i") && operands.operand("i") <= 3))) || (0 <= operands.operand("v") && operands.operand("v") <= 0 && !(((4 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4) || (6 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 6) || (9 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 9)))) || (!(0 <= operands.operand("v") && operands.operand("v") <= 0) && !(((4 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 4) || (6 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 6) || (9 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 9))))) return "one";
  return "other";
}

function selectCardinalR12(operands) {
  if (1 <= operands.operand("i") && operands.operand("i") <= 1 && 0 <= operands.operand("v") && operands.operand("v") <= 0) return "one";
  if (2 <= operands.operand("i") && operands.operand("i") <= 4 && 0 <= operands.operand("v") && operands.operand("v") <= 0) return "few";
  if (!(0 <= operands.operand("v") && operands.operand("v") <= 0)) return "many";
  return "other";
}

function selectCardinalR13(operands) {
  if (Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) return "zero";
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if (Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) return "two";
  if (Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 3) return "few";
  if (Number.isInteger(operands.operand("n")) && 6 <= operands.operand("n") && operands.operand("n") <= 6) return "many";
  return "other";
}

function selectCardinalR14(operands) {
  if ((Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) || (!(0 <= operands.operand("t") && operands.operand("t") <= 0) && ((0 <= operands.operand("i") && operands.operand("i") <= 0) || (1 <= operands.operand("i") && operands.operand("i") <= 1)))) return "one";
  return "other";
}

function selectCardinalR15(operands) {
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && 1 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 1) || (1 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 1)) return "one";
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && 2 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 2) || (2 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 2)) return "two";
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && 3 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 4) || (3 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 4)) return "few";
  return "other";
}

function selectCardinalR16(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if ((0 <= operands.operand("e") && operands.operand("e") <= 0 && !(0 <= operands.operand("i") && operands.operand("i") <= 0) && 0 <= (operands.operand("i") % 1000000) && (operands.operand("i") % 1000000) <= 0 && 0 <= operands.operand("v") && operands.operand("v") <= 0) || (!(0 <= operands.operand("e") && operands.operand("e") <= 5))) return "many";
  return "other";
}

function selectCardinalR17(operands) {
  if ((0 <= operands.operand("i") && operands.operand("i") <= 0) || (1 <= operands.operand("i") && operands.operand("i") <= 1)) return "one";
  return "other";
}

function selectCardinalR18(operands) {
  if ((0 <= operands.operand("i") && operands.operand("i") <= 0) || (1 <= operands.operand("i") && operands.operand("i") <= 1)) return "one";
  if ((0 <= operands.operand("e") && operands.operand("e") <= 0 && !(0 <= operands.operand("i") && operands.operand("i") <= 0) && 0 <= (operands.operand("i") % 1000000) && (operands.operand("i") % 1000000) <= 0 && 0 <= operands.operand("v") && operands.operand("v") <= 0) || (!(0 <= operands.operand("e") && operands.operand("e") <= 5))) return "many";
  return "other";
}

function selectCardinalR19(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if (Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) return "two";
  if (Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 6) return "few";
  if (Number.isInteger(operands.operand("n")) && 7 <= operands.operand("n") && operands.operand("n") <= 10) return "many";
  return "other";
}

function selectCardinalR20(operands) {
  if ((Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) || (Number.isInteger(operands.operand("n")) && 11 <= operands.operand("n") && operands.operand("n") <= 11)) return "one";
  if ((Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) || (Number.isInteger(operands.operand("n")) && 12 <= operands.operand("n") && operands.operand("n") <= 12)) return "two";
  if ((Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 10) || (Number.isInteger(operands.operand("n")) && 13 <= operands.operand("n") && operands.operand("n") <= 19)) return "few";
  return "other";
}

function selectCardinalR21(operands) {
  if (0 <= operands.operand("v") && operands.operand("v") <= 0 && 1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1) return "one";
  if (0 <= operands.operand("v") && operands.operand("v") <= 0 && 2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 2) return "two";
  if (0 <= operands.operand("v") && operands.operand("v") <= 0 && ((0 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 0) || (20 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 20) || (40 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 40) || (60 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 60) || (80 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 80))) return "few";
  if (!(0 <= operands.operand("v") && operands.operand("v") <= 0)) return "many";
  return "other";
}

function selectCardinalR22(operands) {
  if ((1 <= operands.operand("i") && operands.operand("i") <= 1 && 0 <= operands.operand("v") && operands.operand("v") <= 0) || (0 <= operands.operand("i") && operands.operand("i") <= 0 && !(0 <= operands.operand("v") && operands.operand("v") <= 0))) return "one";
  if (2 <= operands.operand("i") && operands.operand("i") <= 2 && 0 <= operands.operand("v") && operands.operand("v") <= 0) return "two";
  return "other";
}

function selectCardinalR23(operands) {
  if ((0 <= operands.operand("t") && operands.operand("t") <= 0 && 1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1 && !(11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11)) || (1 <= (operands.operand("t") % 10) && (operands.operand("t") % 10) <= 1 && !(11 <= (operands.operand("t") % 100) && (operands.operand("t") % 100) <= 11))) return "one";
  return "other";
}

function selectCardinalR24(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if (Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) return "two";
  return "other";
}

function selectCardinalR25(operands) {
  if (Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) return "zero";
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if (((Number.isInteger((operands.operand("n") % 100)) && 2 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 2) || (Number.isInteger((operands.operand("n") % 100)) && 22 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 22) || (Number.isInteger((operands.operand("n") % 100)) && 42 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 42) || (Number.isInteger((operands.operand("n") % 100)) && 62 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 62) || (Number.isInteger((operands.operand("n") % 100)) && 82 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 82)) || (Number.isInteger((operands.operand("n") % 1000)) && 0 <= (operands.operand("n") % 1000) && (operands.operand("n") % 1000) <= 0 && ((Number.isInteger((operands.operand("n") % 100000)) && 1000 <= (operands.operand("n") % 100000) && (operands.operand("n") % 100000) <= 20000) || (Number.isInteger((operands.operand("n") % 100000)) && 40000 <= (operands.operand("n") % 100000) && (operands.operand("n") % 100000) <= 40000) || (Number.isInteger((operands.operand("n") % 100000)) && 60000 <= (operands.operand("n") % 100000) && (operands.operand("n") % 100000) <= 60000) || (Number.isInteger((operands.operand("n") % 100000)) && 80000 <= (operands.operand("n") % 100000) && (operands.operand("n") % 100000) <= 80000))) || (!(Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) && Number.isInteger((operands.operand("n") % 1000000)) && 100000 <= (operands.operand("n") % 1000000) && (operands.operand("n") % 1000000) <= 100000)) return "two";
  if ((Number.isInteger((operands.operand("n") % 100)) && 3 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 3) || (Number.isInteger((operands.operand("n") % 100)) && 23 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 23) || (Number.isInteger((operands.operand("n") % 100)) && 43 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 43) || (Number.isInteger((operands.operand("n") % 100)) && 63 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 63) || (Number.isInteger((operands.operand("n") % 100)) && 83 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 83)) return "few";
  if (!(Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) && ((Number.isInteger((operands.operand("n") % 100)) && 1 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 1) || (Number.isInteger((operands.operand("n") % 100)) && 21 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 21) || (Number.isInteger((operands.operand("n") % 100)) && 41 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 41) || (Number.isInteger((operands.operand("n") % 100)) && 61 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 61) || (Number.isInteger((operands.operand("n") % 100)) && 81 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 81))) return "many";
  return "other";
}

function selectCardinalR26(operands) {
  if (Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) return "zero";
  if (((0 <= operands.operand("i") && operands.operand("i") <= 0) || (1 <= operands.operand("i") && operands.operand("i") <= 1)) && !(Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0)) return "one";
  return "other";
}

function selectCardinalR27(operands) {
  if (Number.isInteger((operands.operand("n") % 10)) && 1 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 1 && !(Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 19)) return "one";
  if (Number.isInteger((operands.operand("n") % 10)) && 2 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 9 && !(Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 19)) return "few";
  if (!(0 <= operands.operand("f") && operands.operand("f") <= 0)) return "many";
  return "other";
}

function selectCardinalR28(operands) {
  if ((Number.isInteger((operands.operand("n") % 10)) && 0 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 0) || (Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 19) || (2 <= operands.operand("v") && operands.operand("v") <= 2 && 11 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 19)) return "zero";
  if ((Number.isInteger((operands.operand("n") % 10)) && 1 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 1 && !(Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 11)) || (2 <= operands.operand("v") && operands.operand("v") <= 2 && 1 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 1 && !(11 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 11)) || (!(2 <= operands.operand("v") && operands.operand("v") <= 2) && 1 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 1)) return "one";
  return "other";
}

function selectCardinalR29(operands) {
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && 1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1 && !(11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11)) || (1 <= (operands.operand("f") % 10) && (operands.operand("f") % 10) <= 1 && !(11 <= (operands.operand("f") % 100) && (operands.operand("f") % 100) <= 11))) return "one";
  return "other";
}

function selectCardinalR30(operands) {
  if (1 <= operands.operand("i") && operands.operand("i") <= 1 && 0 <= operands.operand("v") && operands.operand("v") <= 0) return "one";
  if ((!(0 <= operands.operand("v") && operands.operand("v") <= 0)) || (Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) || (!(Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) && Number.isInteger((operands.operand("n") % 100)) && 1 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 19)) return "few";
  return "other";
}

function selectCardinalR31(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if (Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) return "two";
  if ((Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) || (Number.isInteger((operands.operand("n") % 100)) && 3 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 10)) return "few";
  if (Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 19) return "many";
  return "other";
}

function selectCardinalR32(operands) {
  if (1 <= operands.operand("i") && operands.operand("i") <= 1 && 0 <= operands.operand("v") && operands.operand("v") <= 0) return "one";
  if (0 <= operands.operand("v") && operands.operand("v") <= 0 && 2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4 && !(12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14)) return "few";
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && !(1 <= operands.operand("i") && operands.operand("i") <= 1) && 0 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1) || (0 <= operands.operand("v") && operands.operand("v") <= 0 && 5 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 9) || (0 <= operands.operand("v") && operands.operand("v") <= 0 && 12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14)) return "many";
  return "other";
}

function selectCardinalR33(operands) {
  if (0 <= operands.operand("i") && operands.operand("i") <= 1) return "one";
  if ((0 <= operands.operand("e") && operands.operand("e") <= 0 && !(0 <= operands.operand("i") && operands.operand("i") <= 0) && 0 <= (operands.operand("i") % 1000000) && (operands.operand("i") % 1000000) <= 0 && 0 <= operands.operand("v") && operands.operand("v") <= 0) || (!(0 <= operands.operand("e") && operands.operand("e") <= 5))) return "many";
  return "other";
}

function selectCardinalR34(operands) {
  if (0 <= operands.operand("v") && operands.operand("v") <= 0 && 1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1 && !(11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11)) return "one";
  if (0 <= operands.operand("v") && operands.operand("v") <= 0 && 2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4 && !(12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14)) return "few";
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && 0 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 0) || (0 <= operands.operand("v") && operands.operand("v") <= 0 && 5 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 9) || (0 <= operands.operand("v") && operands.operand("v") <= 0 && 11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 14)) return "many";
  return "other";
}

function selectCardinalR35(operands) {
  if (Number.isInteger((operands.operand("n") % 10)) && 1 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 1 && !(Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 11)) return "one";
  if (Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) return "two";
  if (!(Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) && Number.isInteger((operands.operand("n") % 10)) && 2 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 9 && !(Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 19)) return "few";
  if (!(0 <= operands.operand("f") && operands.operand("f") <= 0)) return "many";
  return "other";
}

function selectCardinalR36(operands) {
  if ((0 <= operands.operand("i") && operands.operand("i") <= 0) || (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1)) return "one";
  if (Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 10) return "few";
  return "other";
}

function selectCardinalR37(operands) {
  if (((Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) || (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1)) || (0 <= operands.operand("i") && operands.operand("i") <= 0 && 1 <= operands.operand("f") && operands.operand("f") <= 1)) return "one";
  return "other";
}

function selectCardinalR38(operands) {
  if (0 <= operands.operand("v") && operands.operand("v") <= 0 && 1 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 1) return "one";
  if (0 <= operands.operand("v") && operands.operand("v") <= 0 && 2 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 2) return "two";
  if ((0 <= operands.operand("v") && operands.operand("v") <= 0 && 3 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 4) || (!(0 <= operands.operand("v") && operands.operand("v") <= 0))) return "few";
  return "other";
}

function selectCardinalR39(operands) {
  if ((Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 1) || (Number.isInteger(operands.operand("n")) && 11 <= operands.operand("n") && operands.operand("n") <= 99)) return "one";
  return "other";
}

function selectOrdinalR0(_operands) {
  return "other";
}

function selectOrdinalR1(operands) {
  if ((Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) || (Number.isInteger(operands.operand("n")) && 5 <= operands.operand("n") && operands.operand("n") <= 5) || (Number.isInteger(operands.operand("n")) && 7 <= operands.operand("n") && operands.operand("n") <= 7) || (Number.isInteger(operands.operand("n")) && 8 <= operands.operand("n") && operands.operand("n") <= 8) || (Number.isInteger(operands.operand("n")) && 9 <= operands.operand("n") && operands.operand("n") <= 9) || (Number.isInteger(operands.operand("n")) && 10 <= operands.operand("n") && operands.operand("n") <= 10)) return "one";
  if ((Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) || (Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 3)) return "two";
  if (Number.isInteger(operands.operand("n")) && 4 <= operands.operand("n") && operands.operand("n") <= 4) return "few";
  if (Number.isInteger(operands.operand("n")) && 6 <= operands.operand("n") && operands.operand("n") <= 6) return "many";
  return "other";
}

function selectOrdinalR2(operands) {
  if (((1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1) || (2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 2) || (5 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 5) || (7 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 7) || (8 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 8)) || ((20 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 20) || (50 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 50) || (70 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 70) || (80 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 80))) return "one";
  if (((3 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 3) || (4 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 4)) || ((100 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 100) || (200 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 200) || (300 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 300) || (400 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 400) || (500 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 500) || (600 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 600) || (700 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 700) || (800 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 800) || (900 <= (operands.operand("i") % 1000) && (operands.operand("i") % 1000) <= 900))) return "few";
  if ((0 <= operands.operand("i") && operands.operand("i") <= 0) || (6 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 6) || ((40 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 40) || (60 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 60) || (90 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 90))) return "many";
  return "other";
}

function selectOrdinalR3(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  return "other";
}

function selectOrdinalR4(operands) {
  if (((Number.isInteger((operands.operand("n") % 10)) && 2 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 2) || (Number.isInteger((operands.operand("n") % 10)) && 3 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 3)) && !(((Number.isInteger((operands.operand("n") % 100)) && 12 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 12) || (Number.isInteger((operands.operand("n") % 100)) && 13 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 13)))) return "few";
  return "other";
}

function selectOrdinalR5(operands) {
  if (0 <= operands.operand("i") && operands.operand("i") <= 0) return "zero";
  if (1 <= operands.operand("i") && operands.operand("i") <= 1) return "one";
  if ((2 <= operands.operand("i") && operands.operand("i") <= 2) || (3 <= operands.operand("i") && operands.operand("i") <= 3) || (4 <= operands.operand("i") && operands.operand("i") <= 4) || (5 <= operands.operand("i") && operands.operand("i") <= 5) || (6 <= operands.operand("i") && operands.operand("i") <= 6)) return "few";
  return "other";
}

function selectOrdinalR6(operands) {
  if ((Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) || (Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 3)) return "one";
  if (Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) return "two";
  if (Number.isInteger(operands.operand("n")) && 4 <= operands.operand("n") && operands.operand("n") <= 4) return "few";
  return "other";
}

function selectOrdinalR7(operands) {
  if ((Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0) || (Number.isInteger(operands.operand("n")) && 7 <= operands.operand("n") && operands.operand("n") <= 7) || (Number.isInteger(operands.operand("n")) && 8 <= operands.operand("n") && operands.operand("n") <= 8) || (Number.isInteger(operands.operand("n")) && 9 <= operands.operand("n") && operands.operand("n") <= 9)) return "zero";
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if (Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) return "two";
  if ((Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 3) || (Number.isInteger(operands.operand("n")) && 4 <= operands.operand("n") && operands.operand("n") <= 4)) return "few";
  if ((Number.isInteger(operands.operand("n")) && 5 <= operands.operand("n") && operands.operand("n") <= 5) || (Number.isInteger(operands.operand("n")) && 6 <= operands.operand("n") && operands.operand("n") <= 6)) return "many";
  return "other";
}

function selectOrdinalR8(operands) {
  if (Number.isInteger((operands.operand("n") % 10)) && 1 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 1 && !(Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 11)) return "one";
  if (Number.isInteger((operands.operand("n") % 10)) && 2 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 2 && !(Number.isInteger((operands.operand("n") % 100)) && 12 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 12)) return "two";
  if (Number.isInteger((operands.operand("n") % 10)) && 3 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 3 && !(Number.isInteger((operands.operand("n") % 100)) && 13 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 13)) return "few";
  return "other";
}

function selectOrdinalR9(operands) {
  if ((Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) || (Number.isInteger(operands.operand("n")) && 11 <= operands.operand("n") && operands.operand("n") <= 11)) return "one";
  if ((Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) || (Number.isInteger(operands.operand("n")) && 12 <= operands.operand("n") && operands.operand("n") <= 12)) return "two";
  if ((Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 3) || (Number.isInteger(operands.operand("n")) && 13 <= operands.operand("n") && operands.operand("n") <= 13)) return "few";
  return "other";
}

function selectOrdinalR10(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if ((Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) || (Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 3)) return "two";
  if (Number.isInteger(operands.operand("n")) && 4 <= operands.operand("n") && operands.operand("n") <= 4) return "few";
  if (Number.isInteger(operands.operand("n")) && 6 <= operands.operand("n") && operands.operand("n") <= 6) return "many";
  return "other";
}

function selectOrdinalR11(operands) {
  if ((Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) || (Number.isInteger(operands.operand("n")) && 5 <= operands.operand("n") && operands.operand("n") <= 5)) return "one";
  return "other";
}

function selectOrdinalR12(operands) {
  if ((Number.isInteger(operands.operand("n")) && 11 <= operands.operand("n") && operands.operand("n") <= 11) || (Number.isInteger(operands.operand("n")) && 8 <= operands.operand("n") && operands.operand("n") <= 8) || (Number.isInteger(operands.operand("n")) && 80 <= operands.operand("n") && operands.operand("n") <= 80) || (Number.isInteger(operands.operand("n")) && 800 <= operands.operand("n") && operands.operand("n") <= 800)) return "many";
  return "other";
}

function selectOrdinalR13(operands) {
  if (1 <= operands.operand("i") && operands.operand("i") <= 1) return "one";
  if ((0 <= operands.operand("i") && operands.operand("i") <= 0) || ((2 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 20) || (40 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 40) || (60 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 60) || (80 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 80))) return "many";
  return "other";
}

function selectOrdinalR14(operands) {
  if ((Number.isInteger((operands.operand("n") % 10)) && 6 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 6) || (Number.isInteger((operands.operand("n") % 10)) && 9 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 9) || (Number.isInteger((operands.operand("n") % 10)) && 0 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 0 && !(Number.isInteger(operands.operand("n")) && 0 <= operands.operand("n") && operands.operand("n") <= 0))) return "many";
  return "other";
}

function selectOrdinalR15(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if ((Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) || (Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 3)) return "two";
  if (Number.isInteger(operands.operand("n")) && 4 <= operands.operand("n") && operands.operand("n") <= 4) return "few";
  return "other";
}

function selectOrdinalR16(operands) {
  if ((Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 4) || ((Number.isInteger((operands.operand("n") % 100)) && 1 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 4) || (Number.isInteger((operands.operand("n") % 100)) && 21 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 24) || (Number.isInteger((operands.operand("n") % 100)) && 41 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 44) || (Number.isInteger((operands.operand("n") % 100)) && 61 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 64) || (Number.isInteger((operands.operand("n") % 100)) && 81 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 84))) return "one";
  if ((Number.isInteger(operands.operand("n")) && 5 <= operands.operand("n") && operands.operand("n") <= 5) || (Number.isInteger((operands.operand("n") % 100)) && 5 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 5)) return "many";
  return "other";
}

function selectOrdinalR17(operands) {
  if ((Number.isInteger(operands.operand("n")) && 11 <= operands.operand("n") && operands.operand("n") <= 11) || (Number.isInteger(operands.operand("n")) && 8 <= operands.operand("n") && operands.operand("n") <= 8) || (Number.isInteger(operands.operand("n")) && 80 <= operands.operand("n") && operands.operand("n") <= 89) || (Number.isInteger(operands.operand("n")) && 800 <= operands.operand("n") && operands.operand("n") <= 899)) return "many";
  return "other";
}

function selectOrdinalR18(operands) {
  if (1 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 1 && !(11 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 11)) return "one";
  if (2 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 2 && !(12 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 12)) return "two";
  if (((7 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 7) || (8 <= (operands.operand("i") % 10) && (operands.operand("i") % 10) <= 8)) && !(((17 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 17) || (18 <= (operands.operand("i") % 100) && (operands.operand("i") % 100) <= 18)))) return "many";
  return "other";
}

function selectOrdinalR19(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 4) return "one";
  return "other";
}

function selectOrdinalR20(operands) {
  if ((Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) || (Number.isInteger(operands.operand("n")) && 5 <= operands.operand("n") && operands.operand("n") <= 5) || (Number.isInteger(operands.operand("n")) && 7 <= operands.operand("n") && operands.operand("n") <= 9)) return "one";
  if ((Number.isInteger(operands.operand("n")) && 2 <= operands.operand("n") && operands.operand("n") <= 2) || (Number.isInteger(operands.operand("n")) && 3 <= operands.operand("n") && operands.operand("n") <= 3)) return "two";
  if (Number.isInteger(operands.operand("n")) && 4 <= operands.operand("n") && operands.operand("n") <= 4) return "few";
  if (Number.isInteger(operands.operand("n")) && 6 <= operands.operand("n") && operands.operand("n") <= 6) return "many";
  return "other";
}

function selectOrdinalR21(operands) {
  if (Number.isInteger(operands.operand("n")) && 1 <= operands.operand("n") && operands.operand("n") <= 1) return "one";
  if (Number.isInteger((operands.operand("n") % 10)) && 4 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 4 && !(Number.isInteger((operands.operand("n") % 100)) && 14 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 14)) return "many";
  return "other";
}

function selectOrdinalR22(operands) {
  if (((Number.isInteger((operands.operand("n") % 10)) && 1 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 1) || (Number.isInteger((operands.operand("n") % 10)) && 2 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 2)) && !(((Number.isInteger((operands.operand("n") % 100)) && 11 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 11) || (Number.isInteger((operands.operand("n") % 100)) && 12 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 12)))) return "one";
  return "other";
}

function selectOrdinalR23(operands) {
  if (((Number.isInteger((operands.operand("n") % 10)) && 6 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 6) || (Number.isInteger((operands.operand("n") % 10)) && 9 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 9)) || (Number.isInteger(operands.operand("n")) && 10 <= operands.operand("n") && operands.operand("n") <= 10)) return "few";
  return "other";
}

function selectOrdinalR24(operands) {
  if (Number.isInteger((operands.operand("n") % 10)) && 3 <= (operands.operand("n") % 10) && (operands.operand("n") % 10) <= 3 && !(Number.isInteger((operands.operand("n") % 100)) && 13 <= (operands.operand("n") % 100) && (operands.operand("n") % 100) <= 13)) return "few";
  return "other";
}
