private enum DirectionSets {
    static let ltrScripts = Set(LocaleDirectionData.LTR_SCRIPTS.split(separator: " ").map(String.init))
    static let rtlScripts = Set(LocaleDirectionData.RTL_SCRIPTS.split(separator: " ").map(String.init))
    static let ltrLanguages = Set(LocaleDirectionData.LTR_LANGUAGES.split(separator: " ").map(String.init))
    static let rtlLanguages = Set(LocaleDirectionData.RTL_LANGUAGES.split(separator: " ").map(String.init))
    static let ltrRegions = Set(LocaleDirectionData.LTR_REGION_OVERRIDES.split(separator: " ").map(String.init))
    static let rtlRegions = Set(LocaleDirectionData.RTL_REGION_OVERRIDES.split(separator: " ").map(String.init))
}

func localeIsLTR(_ locale: String) -> Bool? {
    func direction(_ key: String, ltr: Set<String>, rtl: Set<String>) -> Bool? {
        if ltr.contains(key) { return true }
        if rtl.contains(key) { return false }
        return nil
    }
    let parts = MF2LocaleKey.canonicalKey(locale).split(separator: "-").map(String.init)
    guard let language = parts.first else { return nil }
    let rest = parts.dropFirst()
    if let script = rest.first(where: {
        $0.utf8.count == 4 && $0.utf8.allSatisfy { (65...90).contains($0) || (97...122).contains($0) }
    }) {
        return direction(script, ltr: DirectionSets.ltrScripts, rtl: DirectionSets.rtlScripts)
    }
    let region = rest.first { part in
        (part.utf8.count == 2 && part.utf8.allSatisfy { (65...90).contains($0) })
            || (part.utf8.count == 3 && part.utf8.allSatisfy { (48...57).contains($0) })
    }
    if let region {
        if let result = direction("\(language)-\(region)", ltr: DirectionSets.ltrRegions, rtl: DirectionSets.rtlRegions)
        {
            return result
        }
    } else if language == "und" {
        return nil
    }
    return direction(language, ltr: DirectionSets.ltrLanguages, rtl: DirectionSets.rtlLanguages)
}
