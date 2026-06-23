// Generated from Unicode CLDR by mf2/cldr/update_generated.sh; do not edit by hand.
package mf2

import (
	"math"
	"regexp"
	"strconv"
	"strings"
)

func selectCardinal(locale string, value any) string {
	operands, ok := newNumberOperands(value)
	if !ok {
		return ""
	}
	switch lookupRuleID(cardinalLocales, cardinalParents, locale) {
	case "r0":
		return selectCardinalR0(operands)
	case "r1":
		return selectCardinalR1(operands)
	case "r2":
		return selectCardinalR2(operands)
	case "r3":
		return selectCardinalR3(operands)
	case "r4":
		return selectCardinalR4(operands)
	case "r5":
		return selectCardinalR5(operands)
	case "r6":
		return selectCardinalR6(operands)
	case "r7":
		return selectCardinalR7(operands)
	case "r8":
		return selectCardinalR8(operands)
	case "r9":
		return selectCardinalR9(operands)
	case "r10":
		return selectCardinalR10(operands)
	case "r11":
		return selectCardinalR11(operands)
	case "r12":
		return selectCardinalR12(operands)
	case "r13":
		return selectCardinalR13(operands)
	case "r14":
		return selectCardinalR14(operands)
	case "r15":
		return selectCardinalR15(operands)
	case "r16":
		return selectCardinalR16(operands)
	case "r17":
		return selectCardinalR17(operands)
	case "r18":
		return selectCardinalR18(operands)
	case "r19":
		return selectCardinalR19(operands)
	case "r20":
		return selectCardinalR20(operands)
	case "r21":
		return selectCardinalR21(operands)
	case "r22":
		return selectCardinalR22(operands)
	case "r23":
		return selectCardinalR23(operands)
	case "r24":
		return selectCardinalR24(operands)
	case "r25":
		return selectCardinalR25(operands)
	case "r26":
		return selectCardinalR26(operands)
	case "r27":
		return selectCardinalR27(operands)
	case "r28":
		return selectCardinalR28(operands)
	case "r29":
		return selectCardinalR29(operands)
	case "r30":
		return selectCardinalR30(operands)
	case "r31":
		return selectCardinalR31(operands)
	case "r32":
		return selectCardinalR32(operands)
	case "r33":
		return selectCardinalR33(operands)
	case "r34":
		return selectCardinalR34(operands)
	case "r35":
		return selectCardinalR35(operands)
	case "r36":
		return selectCardinalR36(operands)
	case "r37":
		return selectCardinalR37(operands)
	case "r38":
		return selectCardinalR38(operands)
	case "r39":
		return selectCardinalR39(operands)
	default:
		return "other"
	}
}

func selectOrdinal(locale string, value any) string {
	operands, ok := newNumberOperands(value)
	if !ok {
		return ""
	}
	switch lookupRuleID(ordinalLocales, ordinalParents, locale) {
	case "r0":
		return selectOrdinalR0(operands)
	case "r1":
		return selectOrdinalR1(operands)
	case "r2":
		return selectOrdinalR2(operands)
	case "r3":
		return selectOrdinalR3(operands)
	case "r4":
		return selectOrdinalR4(operands)
	case "r5":
		return selectOrdinalR5(operands)
	case "r6":
		return selectOrdinalR6(operands)
	case "r7":
		return selectOrdinalR7(operands)
	case "r8":
		return selectOrdinalR8(operands)
	case "r9":
		return selectOrdinalR9(operands)
	case "r10":
		return selectOrdinalR10(operands)
	case "r11":
		return selectOrdinalR11(operands)
	case "r12":
		return selectOrdinalR12(operands)
	case "r13":
		return selectOrdinalR13(operands)
	case "r14":
		return selectOrdinalR14(operands)
	case "r15":
		return selectOrdinalR15(operands)
	case "r16":
		return selectOrdinalR16(operands)
	case "r17":
		return selectOrdinalR17(operands)
	case "r18":
		return selectOrdinalR18(operands)
	case "r19":
		return selectOrdinalR19(operands)
	case "r20":
		return selectOrdinalR20(operands)
	case "r21":
		return selectOrdinalR21(operands)
	case "r22":
		return selectOrdinalR22(operands)
	case "r23":
		return selectOrdinalR23(operands)
	case "r24":
		return selectOrdinalR24(operands)
	default:
		return "other"
	}
}

var cardinalLocales = map[string]string{
	"af":       "r0",
	"ak":       "r1",
	"am":       "r2",
	"an":       "r0",
	"ar":       "r3",
	"ars":      "r3",
	"as":       "r2",
	"asa":      "r0",
	"ast":      "r4",
	"az":       "r0",
	"bal":      "r0",
	"be":       "r5",
	"bem":      "r0",
	"bez":      "r0",
	"bg":       "r0",
	"bho":      "r1",
	"blo":      "r6",
	"bm":       "r7",
	"bn":       "r2",
	"bo":       "r7",
	"br":       "r8",
	"brx":      "r0",
	"bs":       "r9",
	"ca":       "r10",
	"ce":       "r0",
	"ceb":      "r11",
	"cgg":      "r0",
	"chr":      "r0",
	"ckb":      "r0",
	"cs":       "r12",
	"csw":      "r1",
	"cv":       "r6",
	"cy":       "r13",
	"da":       "r14",
	"de":       "r4",
	"doi":      "r2",
	"dsb":      "r15",
	"dv":       "r0",
	"dz":       "r7",
	"ee":       "r0",
	"el":       "r0",
	"en":       "r4",
	"eo":       "r0",
	"es":       "r16",
	"et":       "r4",
	"eu":       "r0",
	"fa":       "r2",
	"ff":       "r17",
	"fi":       "r4",
	"fil":      "r11",
	"fo":       "r0",
	"fr":       "r18",
	"fur":      "r0",
	"fy":       "r4",
	"ga":       "r19",
	"gd":       "r20",
	"gl":       "r4",
	"gsw":      "r0",
	"gu":       "r2",
	"guw":      "r1",
	"gv":       "r21",
	"ha":       "r0",
	"haw":      "r0",
	"he":       "r22",
	"hi":       "r2",
	"hnj":      "r7",
	"hr":       "r9",
	"hsb":      "r15",
	"hu":       "r0",
	"hy":       "r17",
	"ia":       "r4",
	"id":       "r7",
	"ie":       "r4",
	"ig":       "r7",
	"ii":       "r7",
	"io":       "r4",
	"is":       "r23",
	"it":       "r10",
	"iu":       "r24",
	"ja":       "r7",
	"jbo":      "r7",
	"jgo":      "r0",
	"jmc":      "r0",
	"jv":       "r7",
	"jw":       "r7",
	"ka":       "r0",
	"kab":      "r17",
	"kaj":      "r0",
	"kcg":      "r0",
	"kde":      "r7",
	"kea":      "r7",
	"kk":       "r0",
	"kkj":      "r0",
	"kl":       "r0",
	"km":       "r7",
	"kn":       "r2",
	"ko":       "r7",
	"kok":      "r2",
	"kok-Latn": "r2",
	"ks":       "r0",
	"ksb":      "r0",
	"ksh":      "r6",
	"ku":       "r0",
	"kw":       "r25",
	"ky":       "r0",
	"lag":      "r26",
	"lb":       "r0",
	"lg":       "r0",
	"lij":      "r4",
	"lkt":      "r7",
	"lld":      "r10",
	"ln":       "r1",
	"lo":       "r7",
	"lt":       "r27",
	"lv":       "r28",
	"mas":      "r0",
	"mg":       "r1",
	"mgo":      "r0",
	"mk":       "r29",
	"ml":       "r0",
	"mn":       "r0",
	"mo":       "r30",
	"mr":       "r0",
	"ms":       "r7",
	"mt":       "r31",
	"my":       "r7",
	"nah":      "r0",
	"naq":      "r24",
	"nb":       "r0",
	"nd":       "r0",
	"ne":       "r0",
	"nl":       "r4",
	"nn":       "r0",
	"nnh":      "r0",
	"no":       "r0",
	"nqo":      "r7",
	"nr":       "r0",
	"nso":      "r1",
	"ny":       "r0",
	"nyn":      "r0",
	"om":       "r0",
	"or":       "r0",
	"os":       "r0",
	"osa":      "r7",
	"pa":       "r1",
	"pap":      "r0",
	"pcm":      "r2",
	"pl":       "r32",
	"prg":      "r28",
	"ps":       "r0",
	"pt":       "r33",
	"pt-PT":    "r10",
	"rm":       "r0",
	"ro":       "r30",
	"rof":      "r0",
	"ru":       "r34",
	"rwk":      "r0",
	"sah":      "r7",
	"saq":      "r0",
	"sat":      "r24",
	"sc":       "r4",
	"scn":      "r10",
	"sd":       "r0",
	"sdh":      "r0",
	"se":       "r24",
	"seh":      "r0",
	"ses":      "r7",
	"sg":       "r7",
	"sgs":      "r35",
	"sh":       "r9",
	"shi":      "r36",
	"si":       "r37",
	"sk":       "r12",
	"sl":       "r38",
	"sma":      "r24",
	"smi":      "r24",
	"smj":      "r24",
	"smn":      "r24",
	"sms":      "r24",
	"sn":       "r0",
	"so":       "r0",
	"sq":       "r0",
	"sr":       "r9",
	"ss":       "r0",
	"ssy":      "r0",
	"st":       "r0",
	"su":       "r7",
	"sv":       "r4",
	"sw":       "r4",
	"syr":      "r0",
	"ta":       "r0",
	"te":       "r0",
	"teo":      "r0",
	"th":       "r7",
	"ti":       "r1",
	"tig":      "r0",
	"tk":       "r0",
	"tl":       "r11",
	"tn":       "r0",
	"to":       "r7",
	"tpi":      "r7",
	"tr":       "r0",
	"ts":       "r0",
	"tzm":      "r39",
	"ug":       "r0",
	"uk":       "r34",
	"und":      "r7",
	"ur":       "r4",
	"uz":       "r0",
	"ve":       "r0",
	"vec":      "r10",
	"vi":       "r7",
	"vo":       "r0",
	"vun":      "r0",
	"wa":       "r1",
	"wae":      "r0",
	"wo":       "r7",
	"xh":       "r0",
	"xog":      "r0",
	"yi":       "r4",
	"yo":       "r7",
	"yue":      "r7",
	"zh":       "r7",
	"zu":       "r2",
}

var ordinalLocales = map[string]string{
	"af":       "r0",
	"am":       "r0",
	"an":       "r0",
	"ar":       "r0",
	"as":       "r1",
	"ast":      "r0",
	"az":       "r2",
	"bal":      "r3",
	"be":       "r4",
	"bg":       "r0",
	"blo":      "r5",
	"bn":       "r1",
	"bs":       "r0",
	"ca":       "r6",
	"ce":       "r0",
	"cs":       "r0",
	"cv":       "r0",
	"cy":       "r7",
	"da":       "r0",
	"de":       "r0",
	"dsb":      "r0",
	"el":       "r0",
	"en":       "r8",
	"es":       "r0",
	"et":       "r0",
	"eu":       "r0",
	"fa":       "r0",
	"fi":       "r0",
	"fil":      "r3",
	"fr":       "r3",
	"fy":       "r0",
	"ga":       "r3",
	"gd":       "r9",
	"gl":       "r0",
	"gsw":      "r0",
	"gu":       "r10",
	"he":       "r0",
	"hi":       "r10",
	"hr":       "r0",
	"hsb":      "r0",
	"hu":       "r11",
	"hy":       "r3",
	"ia":       "r0",
	"id":       "r0",
	"ie":       "r0",
	"is":       "r0",
	"it":       "r12",
	"ja":       "r0",
	"ka":       "r13",
	"kk":       "r14",
	"km":       "r0",
	"kn":       "r0",
	"ko":       "r0",
	"kok":      "r15",
	"kok-Latn": "r15",
	"kw":       "r16",
	"ky":       "r0",
	"lij":      "r17",
	"lld":      "r12",
	"lo":       "r3",
	"lt":       "r0",
	"lv":       "r0",
	"mk":       "r18",
	"ml":       "r0",
	"mn":       "r0",
	"mo":       "r3",
	"mr":       "r15",
	"ms":       "r3",
	"my":       "r0",
	"nb":       "r0",
	"ne":       "r19",
	"nl":       "r0",
	"no":       "r0",
	"or":       "r20",
	"pa":       "r0",
	"pl":       "r0",
	"prg":      "r0",
	"ps":       "r0",
	"pt":       "r0",
	"ro":       "r3",
	"ru":       "r0",
	"sc":       "r12",
	"scn":      "r17",
	"sd":       "r0",
	"sh":       "r0",
	"si":       "r0",
	"sk":       "r0",
	"sl":       "r0",
	"sq":       "r21",
	"sr":       "r0",
	"sv":       "r22",
	"sw":       "r0",
	"ta":       "r0",
	"te":       "r0",
	"th":       "r0",
	"tk":       "r23",
	"tl":       "r3",
	"tpi":      "r0",
	"tr":       "r0",
	"uk":       "r24",
	"und":      "r0",
	"ur":       "r0",
	"uz":       "r0",
	"vec":      "r12",
	"vi":       "r3",
	"yue":      "r0",
	"zh":       "r0",
	"zu":       "r0",
}

var cardinalParents = map[string]string{}

var ordinalParents = map[string]string{}

func lookupRuleID(locales map[string]string, parents map[string]string, locale string) string {
	for _, candidate := range pluralLookupChain(locale, parents) {
		if rule := locales[candidate]; rule != "" {
			return rule
		}
	}
	return ""
}

type numberOperands struct {
	n float64
	i int64
	v int64
	w int64
	f int64
	t int64
	e int64
	c int64
}

const maxPluralOperandLength = 256
const maxSafePluralInteger = 9007199254740991.0
const maxSafePluralInt = 9007199254740991

var pluralOperandRe = regexp.MustCompile(`^[+-]?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$`)

func newNumberOperands(value any) (numberOperands, bool) {
	raw := strings.TrimSpace(valueToString(value))
	if raw == "" || len(raw) > maxPluralOperandLength {
		return numberOperands{}, false
	}
	if !pluralOperandRe.MatchString(raw) {
		return numberOperands{}, false
	}
	parsed, err := strconv.ParseFloat(raw, 64)
	if err != nil || math.IsInf(parsed, 0) || math.IsNaN(parsed) {
		return numberOperands{}, false
	}
	n := math.Abs(parsed)
	if n > maxSafePluralInteger {
		return numberOperands{}, false
	}
	normalized := strings.ToLower(strings.TrimLeft(raw, "+-"))
	base := strings.SplitN(normalized, "e", 2)[0]
	fraction := ""
	if dot := strings.Index(base, "."); dot >= 0 {
		fraction = base[dot+1:]
	}
	trimmedFraction := strings.TrimRight(fraction, "0")
	f, ok := parsePluralDigits(fraction)
	if !ok {
		return numberOperands{}, false
	}
	t, ok := parsePluralDigits(trimmedFraction)
	if !ok {
		return numberOperands{}, false
	}
	return numberOperands{
		n: n,
		i: int64(math.Trunc(n)),
		v: int64(len(fraction)),
		w: int64(len(trimmedFraction)),
		f: f,
		t: t,
		e: 0,
		c: 0,
	}, true
}

func parsePluralDigits(value string) (int64, bool) {
	if value == "" {
		return 0, true
	}
	digits := strings.TrimLeft(value, "0")
	if digits == "" {
		digits = "0"
	}
	parsed, err := strconv.ParseInt(digits, 10, 64)
	if err != nil || parsed > maxSafePluralInt {
		return 0, false
	}
	return parsed, true
}

func (operands numberOperands) operand(name string) float64 {
	switch name {
	case "n":
		return operands.n
	case "i":
		return float64(operands.i)
	case "v":
		return float64(operands.v)
	case "w":
		return float64(operands.w)
	case "f":
		return float64(operands.f)
	case "t":
		return float64(operands.t)
	case "e":
		return float64(operands.e)
	case "c":
		return float64(operands.c)
	default:
		return 0
	}
}

func selectCardinalR0(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	return "other"
}

func selectCardinalR1(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	return "other"
}

func selectCardinalR2(operands numberOperands) string {
	if (0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) {
		return "one"
	}
	return "other"
}

func selectCardinalR3(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0 {
		return "zero"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0 {
		return "two"
	}
	if math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 3.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 10.0 {
		return "few"
	}
	if math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 99.0 {
		return "many"
	}
	return "other"
}

func selectCardinalR4(operands numberOperands) string {
	if 1.0 <= operands.operand("i") && operands.operand("i") <= 1.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 {
		return "one"
	}
	return "other"
}

func selectCardinalR5(operands numberOperands) string {
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 1.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 1.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 11.0) {
		return "one"
	}
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 2.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 4.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 12.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 14.0) {
		return "few"
	}
	if (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 0.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 0.0) || (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 5.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 9.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 14.0) {
		return "many"
	}
	return "other"
}

func selectCardinalR6(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0 {
		return "zero"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	return "other"
}

func selectCardinalR7(_ numberOperands) string {
	return "other"
}

func selectCardinalR8(operands numberOperands) string {
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 1.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 1.0 && !((math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 11.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 71.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 71.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 91.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 91.0)) {
		return "one"
	}
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 2.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 2.0 && !((math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 12.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 12.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 72.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 72.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 92.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 92.0)) {
		return "two"
	}
	if ((math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 3.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 4.0) || (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 9.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 9.0)) && !((math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 10.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 19.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 70.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 79.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 90.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 99.0)) {
		return "few"
	}
	if !(math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0) && math.Trunc(math.Mod(operands.operand("n"), 1000000.0)) == math.Mod(operands.operand("n"), 1000000.0) && 0.0 <= math.Mod(operands.operand("n"), 1000000.0) && math.Mod(operands.operand("n"), 1000000.0) <= 0.0 {
		return "many"
	}
	return "other"
}

func selectCardinalR9(operands numberOperands) string {
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 1.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 1.0 && !(11.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 11.0)) || (1.0 <= math.Mod(operands.operand("f"), 10.0) && math.Mod(operands.operand("f"), 10.0) <= 1.0 && !(11.0 <= math.Mod(operands.operand("f"), 100.0) && math.Mod(operands.operand("f"), 100.0) <= 11.0)) {
		return "one"
	}
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 2.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 4.0 && !(12.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 14.0)) || (2.0 <= math.Mod(operands.operand("f"), 10.0) && math.Mod(operands.operand("f"), 10.0) <= 4.0 && !(12.0 <= math.Mod(operands.operand("f"), 100.0) && math.Mod(operands.operand("f"), 100.0) <= 14.0)) {
		return "few"
	}
	return "other"
}

func selectCardinalR10(operands numberOperands) string {
	if 1.0 <= operands.operand("i") && operands.operand("i") <= 1.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 {
		return "one"
	}
	if (0.0 <= operands.operand("e") && operands.operand("e") <= 0.0 && !(0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) && 0.0 <= math.Mod(operands.operand("i"), 1000000.0) && math.Mod(operands.operand("i"), 1000000.0) <= 0.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0) || (!(0.0 <= operands.operand("e") && operands.operand("e") <= 5.0)) {
		return "many"
	}
	return "other"
}

func selectCardinalR11(operands numberOperands) string {
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && ((1.0 <= operands.operand("i") && operands.operand("i") <= 1.0) || (2.0 <= operands.operand("i") && operands.operand("i") <= 2.0) || (3.0 <= operands.operand("i") && operands.operand("i") <= 3.0))) || (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && !((4.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 4.0) || (6.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 6.0) || (9.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 9.0))) || (!(0.0 <= operands.operand("v") && operands.operand("v") <= 0.0) && !((4.0 <= math.Mod(operands.operand("f"), 10.0) && math.Mod(operands.operand("f"), 10.0) <= 4.0) || (6.0 <= math.Mod(operands.operand("f"), 10.0) && math.Mod(operands.operand("f"), 10.0) <= 6.0) || (9.0 <= math.Mod(operands.operand("f"), 10.0) && math.Mod(operands.operand("f"), 10.0) <= 9.0))) {
		return "one"
	}
	return "other"
}

func selectCardinalR12(operands numberOperands) string {
	if 1.0 <= operands.operand("i") && operands.operand("i") <= 1.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 {
		return "one"
	}
	if 2.0 <= operands.operand("i") && operands.operand("i") <= 4.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 {
		return "few"
	}
	if !(0.0 <= operands.operand("v") && operands.operand("v") <= 0.0) {
		return "many"
	}
	return "other"
}

func selectCardinalR13(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0 {
		return "zero"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0 {
		return "two"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 3.0 {
		return "few"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 6.0 <= operands.operand("n") && operands.operand("n") <= 6.0 {
		return "many"
	}
	return "other"
}

func selectCardinalR14(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) || (!(0.0 <= operands.operand("t") && operands.operand("t") <= 0.0) && ((0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) || (1.0 <= operands.operand("i") && operands.operand("i") <= 1.0))) {
		return "one"
	}
	return "other"
}

func selectCardinalR15(operands numberOperands) string {
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 1.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 1.0) || (1.0 <= math.Mod(operands.operand("f"), 100.0) && math.Mod(operands.operand("f"), 100.0) <= 1.0) {
		return "one"
	}
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 2.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 2.0) || (2.0 <= math.Mod(operands.operand("f"), 100.0) && math.Mod(operands.operand("f"), 100.0) <= 2.0) {
		return "two"
	}
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 3.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 4.0) || (3.0 <= math.Mod(operands.operand("f"), 100.0) && math.Mod(operands.operand("f"), 100.0) <= 4.0) {
		return "few"
	}
	return "other"
}

func selectCardinalR16(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if (0.0 <= operands.operand("e") && operands.operand("e") <= 0.0 && !(0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) && 0.0 <= math.Mod(operands.operand("i"), 1000000.0) && math.Mod(operands.operand("i"), 1000000.0) <= 0.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0) || (!(0.0 <= operands.operand("e") && operands.operand("e") <= 5.0)) {
		return "many"
	}
	return "other"
}

func selectCardinalR17(operands numberOperands) string {
	if (0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) || (1.0 <= operands.operand("i") && operands.operand("i") <= 1.0) {
		return "one"
	}
	return "other"
}

func selectCardinalR18(operands numberOperands) string {
	if (0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) || (1.0 <= operands.operand("i") && operands.operand("i") <= 1.0) {
		return "one"
	}
	if (0.0 <= operands.operand("e") && operands.operand("e") <= 0.0 && !(0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) && 0.0 <= math.Mod(operands.operand("i"), 1000000.0) && math.Mod(operands.operand("i"), 1000000.0) <= 0.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0) || (!(0.0 <= operands.operand("e") && operands.operand("e") <= 5.0)) {
		return "many"
	}
	return "other"
}

func selectCardinalR19(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0 {
		return "two"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 6.0 {
		return "few"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 7.0 <= operands.operand("n") && operands.operand("n") <= 10.0 {
		return "many"
	}
	return "other"
}

func selectCardinalR20(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 11.0 <= operands.operand("n") && operands.operand("n") <= 11.0) {
		return "one"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 12.0 <= operands.operand("n") && operands.operand("n") <= 12.0) {
		return "two"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 10.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 13.0 <= operands.operand("n") && operands.operand("n") <= 19.0) {
		return "few"
	}
	return "other"
}

func selectCardinalR21(operands numberOperands) string {
	if 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 1.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 1.0 {
		return "one"
	}
	if 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 2.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 2.0 {
		return "two"
	}
	if 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && ((0.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 0.0) || (20.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 20.0) || (40.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 40.0) || (60.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 60.0) || (80.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 80.0)) {
		return "few"
	}
	if !(0.0 <= operands.operand("v") && operands.operand("v") <= 0.0) {
		return "many"
	}
	return "other"
}

func selectCardinalR22(operands numberOperands) string {
	if (1.0 <= operands.operand("i") && operands.operand("i") <= 1.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0) || (0.0 <= operands.operand("i") && operands.operand("i") <= 0.0 && !(0.0 <= operands.operand("v") && operands.operand("v") <= 0.0)) {
		return "one"
	}
	if 2.0 <= operands.operand("i") && operands.operand("i") <= 2.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 {
		return "two"
	}
	return "other"
}

func selectCardinalR23(operands numberOperands) string {
	if (0.0 <= operands.operand("t") && operands.operand("t") <= 0.0 && 1.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 1.0 && !(11.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 11.0)) || (1.0 <= math.Mod(operands.operand("t"), 10.0) && math.Mod(operands.operand("t"), 10.0) <= 1.0 && !(11.0 <= math.Mod(operands.operand("t"), 100.0) && math.Mod(operands.operand("t"), 100.0) <= 11.0)) {
		return "one"
	}
	return "other"
}

func selectCardinalR24(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0 {
		return "two"
	}
	return "other"
}

func selectCardinalR25(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0 {
		return "zero"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if ((math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 2.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 2.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 22.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 22.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 42.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 42.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 62.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 62.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 82.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 82.0)) || (math.Trunc(math.Mod(operands.operand("n"), 1000.0)) == math.Mod(operands.operand("n"), 1000.0) && 0.0 <= math.Mod(operands.operand("n"), 1000.0) && math.Mod(operands.operand("n"), 1000.0) <= 0.0 && ((math.Trunc(math.Mod(operands.operand("n"), 100000.0)) == math.Mod(operands.operand("n"), 100000.0) && 1000.0 <= math.Mod(operands.operand("n"), 100000.0) && math.Mod(operands.operand("n"), 100000.0) <= 20000.0) || (math.Trunc(math.Mod(operands.operand("n"), 100000.0)) == math.Mod(operands.operand("n"), 100000.0) && 40000.0 <= math.Mod(operands.operand("n"), 100000.0) && math.Mod(operands.operand("n"), 100000.0) <= 40000.0) || (math.Trunc(math.Mod(operands.operand("n"), 100000.0)) == math.Mod(operands.operand("n"), 100000.0) && 60000.0 <= math.Mod(operands.operand("n"), 100000.0) && math.Mod(operands.operand("n"), 100000.0) <= 60000.0) || (math.Trunc(math.Mod(operands.operand("n"), 100000.0)) == math.Mod(operands.operand("n"), 100000.0) && 80000.0 <= math.Mod(operands.operand("n"), 100000.0) && math.Mod(operands.operand("n"), 100000.0) <= 80000.0))) || (!(math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0) && math.Trunc(math.Mod(operands.operand("n"), 1000000.0)) == math.Mod(operands.operand("n"), 1000000.0) && 100000.0 <= math.Mod(operands.operand("n"), 1000000.0) && math.Mod(operands.operand("n"), 1000000.0) <= 100000.0) {
		return "two"
	}
	if (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 3.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 3.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 23.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 23.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 43.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 43.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 63.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 63.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 83.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 83.0) {
		return "few"
	}
	if !(math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) && ((math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 1.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 1.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 21.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 21.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 41.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 41.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 61.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 61.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 81.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 81.0)) {
		return "many"
	}
	return "other"
}

func selectCardinalR26(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0 {
		return "zero"
	}
	if ((0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) || (1.0 <= operands.operand("i") && operands.operand("i") <= 1.0)) && !(math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0) {
		return "one"
	}
	return "other"
}

func selectCardinalR27(operands numberOperands) string {
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 1.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 1.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 19.0) {
		return "one"
	}
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 2.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 9.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 19.0) {
		return "few"
	}
	if !(0.0 <= operands.operand("f") && operands.operand("f") <= 0.0) {
		return "many"
	}
	return "other"
}

func selectCardinalR28(operands numberOperands) string {
	if (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 0.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 0.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 19.0) || (2.0 <= operands.operand("v") && operands.operand("v") <= 2.0 && 11.0 <= math.Mod(operands.operand("f"), 100.0) && math.Mod(operands.operand("f"), 100.0) <= 19.0) {
		return "zero"
	}
	if (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 1.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 1.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 11.0)) || (2.0 <= operands.operand("v") && operands.operand("v") <= 2.0 && 1.0 <= math.Mod(operands.operand("f"), 10.0) && math.Mod(operands.operand("f"), 10.0) <= 1.0 && !(11.0 <= math.Mod(operands.operand("f"), 100.0) && math.Mod(operands.operand("f"), 100.0) <= 11.0)) || (!(2.0 <= operands.operand("v") && operands.operand("v") <= 2.0) && 1.0 <= math.Mod(operands.operand("f"), 10.0) && math.Mod(operands.operand("f"), 10.0) <= 1.0) {
		return "one"
	}
	return "other"
}

func selectCardinalR29(operands numberOperands) string {
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 1.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 1.0 && !(11.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 11.0)) || (1.0 <= math.Mod(operands.operand("f"), 10.0) && math.Mod(operands.operand("f"), 10.0) <= 1.0 && !(11.0 <= math.Mod(operands.operand("f"), 100.0) && math.Mod(operands.operand("f"), 100.0) <= 11.0)) {
		return "one"
	}
	return "other"
}

func selectCardinalR30(operands numberOperands) string {
	if 1.0 <= operands.operand("i") && operands.operand("i") <= 1.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 {
		return "one"
	}
	if (!(0.0 <= operands.operand("v") && operands.operand("v") <= 0.0)) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0) || (!(math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) && math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 1.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 19.0) {
		return "few"
	}
	return "other"
}

func selectCardinalR31(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0 {
		return "two"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 3.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 10.0) {
		return "few"
	}
	if math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 19.0 {
		return "many"
	}
	return "other"
}

func selectCardinalR32(operands numberOperands) string {
	if 1.0 <= operands.operand("i") && operands.operand("i") <= 1.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 {
		return "one"
	}
	if 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 2.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 4.0 && !(12.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 14.0) {
		return "few"
	}
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && !(1.0 <= operands.operand("i") && operands.operand("i") <= 1.0) && 0.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 1.0) || (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 5.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 9.0) || (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 12.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 14.0) {
		return "many"
	}
	return "other"
}

func selectCardinalR33(operands numberOperands) string {
	if 0.0 <= operands.operand("i") && operands.operand("i") <= 1.0 {
		return "one"
	}
	if (0.0 <= operands.operand("e") && operands.operand("e") <= 0.0 && !(0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) && 0.0 <= math.Mod(operands.operand("i"), 1000000.0) && math.Mod(operands.operand("i"), 1000000.0) <= 0.0 && 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0) || (!(0.0 <= operands.operand("e") && operands.operand("e") <= 5.0)) {
		return "many"
	}
	return "other"
}

func selectCardinalR34(operands numberOperands) string {
	if 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 1.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 1.0 && !(11.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 11.0) {
		return "one"
	}
	if 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 2.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 4.0 && !(12.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 14.0) {
		return "few"
	}
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 0.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 0.0) || (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 5.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 9.0) || (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 11.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 14.0) {
		return "many"
	}
	return "other"
}

func selectCardinalR35(operands numberOperands) string {
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 1.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 1.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 11.0) {
		return "one"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0 {
		return "two"
	}
	if !(math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0) && math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 2.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 9.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 19.0) {
		return "few"
	}
	if !(0.0 <= operands.operand("f") && operands.operand("f") <= 0.0) {
		return "many"
	}
	return "other"
}

func selectCardinalR36(operands numberOperands) string {
	if (0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) {
		return "one"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 10.0 {
		return "few"
	}
	return "other"
}

func selectCardinalR37(operands numberOperands) string {
	if ((math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0)) || (0.0 <= operands.operand("i") && operands.operand("i") <= 0.0 && 1.0 <= operands.operand("f") && operands.operand("f") <= 1.0) {
		return "one"
	}
	return "other"
}

func selectCardinalR38(operands numberOperands) string {
	if 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 1.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 1.0 {
		return "one"
	}
	if 0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 2.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 2.0 {
		return "two"
	}
	if (0.0 <= operands.operand("v") && operands.operand("v") <= 0.0 && 3.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 4.0) || (!(0.0 <= operands.operand("v") && operands.operand("v") <= 0.0)) {
		return "few"
	}
	return "other"
}

func selectCardinalR39(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 1.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 11.0 <= operands.operand("n") && operands.operand("n") <= 99.0) {
		return "one"
	}
	return "other"
}

func selectOrdinalR0(_ numberOperands) string {
	return "other"
}

func selectOrdinalR1(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 5.0 <= operands.operand("n") && operands.operand("n") <= 5.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 7.0 <= operands.operand("n") && operands.operand("n") <= 7.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 8.0 <= operands.operand("n") && operands.operand("n") <= 8.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 9.0 <= operands.operand("n") && operands.operand("n") <= 9.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 10.0 <= operands.operand("n") && operands.operand("n") <= 10.0) {
		return "one"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 3.0) {
		return "two"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 4.0 <= operands.operand("n") && operands.operand("n") <= 4.0 {
		return "few"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 6.0 <= operands.operand("n") && operands.operand("n") <= 6.0 {
		return "many"
	}
	return "other"
}

func selectOrdinalR2(operands numberOperands) string {
	if ((1.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 1.0) || (2.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 2.0) || (5.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 5.0) || (7.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 7.0) || (8.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 8.0)) || ((20.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 20.0) || (50.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 50.0) || (70.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 70.0) || (80.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 80.0)) {
		return "one"
	}
	if ((3.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 3.0) || (4.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 4.0)) || ((100.0 <= math.Mod(operands.operand("i"), 1000.0) && math.Mod(operands.operand("i"), 1000.0) <= 100.0) || (200.0 <= math.Mod(operands.operand("i"), 1000.0) && math.Mod(operands.operand("i"), 1000.0) <= 200.0) || (300.0 <= math.Mod(operands.operand("i"), 1000.0) && math.Mod(operands.operand("i"), 1000.0) <= 300.0) || (400.0 <= math.Mod(operands.operand("i"), 1000.0) && math.Mod(operands.operand("i"), 1000.0) <= 400.0) || (500.0 <= math.Mod(operands.operand("i"), 1000.0) && math.Mod(operands.operand("i"), 1000.0) <= 500.0) || (600.0 <= math.Mod(operands.operand("i"), 1000.0) && math.Mod(operands.operand("i"), 1000.0) <= 600.0) || (700.0 <= math.Mod(operands.operand("i"), 1000.0) && math.Mod(operands.operand("i"), 1000.0) <= 700.0) || (800.0 <= math.Mod(operands.operand("i"), 1000.0) && math.Mod(operands.operand("i"), 1000.0) <= 800.0) || (900.0 <= math.Mod(operands.operand("i"), 1000.0) && math.Mod(operands.operand("i"), 1000.0) <= 900.0)) {
		return "few"
	}
	if (0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) || (6.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 6.0) || ((40.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 40.0) || (60.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 60.0) || (90.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 90.0)) {
		return "many"
	}
	return "other"
}

func selectOrdinalR3(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	return "other"
}

func selectOrdinalR4(operands numberOperands) string {
	if ((math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 2.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 2.0) || (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 3.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 3.0)) && !((math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 12.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 12.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 13.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 13.0)) {
		return "few"
	}
	return "other"
}

func selectOrdinalR5(operands numberOperands) string {
	if 0.0 <= operands.operand("i") && operands.operand("i") <= 0.0 {
		return "zero"
	}
	if 1.0 <= operands.operand("i") && operands.operand("i") <= 1.0 {
		return "one"
	}
	if (2.0 <= operands.operand("i") && operands.operand("i") <= 2.0) || (3.0 <= operands.operand("i") && operands.operand("i") <= 3.0) || (4.0 <= operands.operand("i") && operands.operand("i") <= 4.0) || (5.0 <= operands.operand("i") && operands.operand("i") <= 5.0) || (6.0 <= operands.operand("i") && operands.operand("i") <= 6.0) {
		return "few"
	}
	return "other"
}

func selectOrdinalR6(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 3.0) {
		return "one"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0 {
		return "two"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 4.0 <= operands.operand("n") && operands.operand("n") <= 4.0 {
		return "few"
	}
	return "other"
}

func selectOrdinalR7(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 7.0 <= operands.operand("n") && operands.operand("n") <= 7.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 8.0 <= operands.operand("n") && operands.operand("n") <= 8.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 9.0 <= operands.operand("n") && operands.operand("n") <= 9.0) {
		return "zero"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0 {
		return "two"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 3.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 4.0 <= operands.operand("n") && operands.operand("n") <= 4.0) {
		return "few"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 5.0 <= operands.operand("n") && operands.operand("n") <= 5.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 6.0 <= operands.operand("n") && operands.operand("n") <= 6.0) {
		return "many"
	}
	return "other"
}

func selectOrdinalR8(operands numberOperands) string {
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 1.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 1.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 11.0) {
		return "one"
	}
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 2.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 2.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 12.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 12.0) {
		return "two"
	}
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 3.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 3.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 13.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 13.0) {
		return "few"
	}
	return "other"
}

func selectOrdinalR9(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 11.0 <= operands.operand("n") && operands.operand("n") <= 11.0) {
		return "one"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 12.0 <= operands.operand("n") && operands.operand("n") <= 12.0) {
		return "two"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 3.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 13.0 <= operands.operand("n") && operands.operand("n") <= 13.0) {
		return "few"
	}
	return "other"
}

func selectOrdinalR10(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 3.0) {
		return "two"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 4.0 <= operands.operand("n") && operands.operand("n") <= 4.0 {
		return "few"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 6.0 <= operands.operand("n") && operands.operand("n") <= 6.0 {
		return "many"
	}
	return "other"
}

func selectOrdinalR11(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 5.0 <= operands.operand("n") && operands.operand("n") <= 5.0) {
		return "one"
	}
	return "other"
}

func selectOrdinalR12(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 11.0 <= operands.operand("n") && operands.operand("n") <= 11.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 8.0 <= operands.operand("n") && operands.operand("n") <= 8.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 80.0 <= operands.operand("n") && operands.operand("n") <= 80.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 800.0 <= operands.operand("n") && operands.operand("n") <= 800.0) {
		return "many"
	}
	return "other"
}

func selectOrdinalR13(operands numberOperands) string {
	if 1.0 <= operands.operand("i") && operands.operand("i") <= 1.0 {
		return "one"
	}
	if (0.0 <= operands.operand("i") && operands.operand("i") <= 0.0) || ((2.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 20.0) || (40.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 40.0) || (60.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 60.0) || (80.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 80.0)) {
		return "many"
	}
	return "other"
}

func selectOrdinalR14(operands numberOperands) string {
	if (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 6.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 6.0) || (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 9.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 9.0) || (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 0.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 0.0 && !(math.Trunc(operands.operand("n")) == operands.operand("n") && 0.0 <= operands.operand("n") && operands.operand("n") <= 0.0)) {
		return "many"
	}
	return "other"
}

func selectOrdinalR15(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 3.0) {
		return "two"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 4.0 <= operands.operand("n") && operands.operand("n") <= 4.0 {
		return "few"
	}
	return "other"
}

func selectOrdinalR16(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 4.0) || ((math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 1.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 4.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 21.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 24.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 41.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 44.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 61.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 64.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 81.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 84.0)) {
		return "one"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 5.0 <= operands.operand("n") && operands.operand("n") <= 5.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 5.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 5.0) {
		return "many"
	}
	return "other"
}

func selectOrdinalR17(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 11.0 <= operands.operand("n") && operands.operand("n") <= 11.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 8.0 <= operands.operand("n") && operands.operand("n") <= 8.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 80.0 <= operands.operand("n") && operands.operand("n") <= 89.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 800.0 <= operands.operand("n") && operands.operand("n") <= 899.0) {
		return "many"
	}
	return "other"
}

func selectOrdinalR18(operands numberOperands) string {
	if 1.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 1.0 && !(11.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 11.0) {
		return "one"
	}
	if 2.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 2.0 && !(12.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 12.0) {
		return "two"
	}
	if ((7.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 7.0) || (8.0 <= math.Mod(operands.operand("i"), 10.0) && math.Mod(operands.operand("i"), 10.0) <= 8.0)) && !((17.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 17.0) || (18.0 <= math.Mod(operands.operand("i"), 100.0) && math.Mod(operands.operand("i"), 100.0) <= 18.0)) {
		return "many"
	}
	return "other"
}

func selectOrdinalR19(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 4.0 {
		return "one"
	}
	return "other"
}

func selectOrdinalR20(operands numberOperands) string {
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 5.0 <= operands.operand("n") && operands.operand("n") <= 5.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 7.0 <= operands.operand("n") && operands.operand("n") <= 9.0) {
		return "one"
	}
	if (math.Trunc(operands.operand("n")) == operands.operand("n") && 2.0 <= operands.operand("n") && operands.operand("n") <= 2.0) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 3.0 <= operands.operand("n") && operands.operand("n") <= 3.0) {
		return "two"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 4.0 <= operands.operand("n") && operands.operand("n") <= 4.0 {
		return "few"
	}
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 6.0 <= operands.operand("n") && operands.operand("n") <= 6.0 {
		return "many"
	}
	return "other"
}

func selectOrdinalR21(operands numberOperands) string {
	if math.Trunc(operands.operand("n")) == operands.operand("n") && 1.0 <= operands.operand("n") && operands.operand("n") <= 1.0 {
		return "one"
	}
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 4.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 4.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 14.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 14.0) {
		return "many"
	}
	return "other"
}

func selectOrdinalR22(operands numberOperands) string {
	if ((math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 1.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 1.0) || (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 2.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 2.0)) && !((math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 11.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 11.0) || (math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 12.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 12.0)) {
		return "one"
	}
	return "other"
}

func selectOrdinalR23(operands numberOperands) string {
	if ((math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 6.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 6.0) || (math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 9.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 9.0)) || (math.Trunc(operands.operand("n")) == operands.operand("n") && 10.0 <= operands.operand("n") && operands.operand("n") <= 10.0) {
		return "few"
	}
	return "other"
}

func selectOrdinalR24(operands numberOperands) string {
	if math.Trunc(math.Mod(operands.operand("n"), 10.0)) == math.Mod(operands.operand("n"), 10.0) && 3.0 <= math.Mod(operands.operand("n"), 10.0) && math.Mod(operands.operand("n"), 10.0) <= 3.0 && !(math.Trunc(math.Mod(operands.operand("n"), 100.0)) == math.Mod(operands.operand("n"), 100.0) && 13.0 <= math.Mod(operands.operand("n"), 100.0) && math.Mod(operands.operand("n"), 100.0) <= 13.0) {
		return "few"
	}
	return "other"
}
