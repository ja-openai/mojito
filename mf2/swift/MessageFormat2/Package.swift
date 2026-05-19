// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "MessageFormat2",
    platforms: [
        .macOS(.v14),
        .iOS(.v17),
    ],
    products: [
        .library(
            name: "MessageFormat2Runtime",
            targets: ["MessageFormat2Runtime"]
        ),
        .executable(
            name: "MessageFormat2Conformance",
            targets: ["MessageFormat2Conformance"]
        ),
        .executable(
            name: "MessageFormat2TranslateDemo",
            targets: ["MessageFormat2TranslateDemo"]
        ),
    ],
    targets: [
        .target(name: "MessageFormat2Runtime"),
        .executableTarget(
            name: "MessageFormat2Conformance",
            dependencies: ["MessageFormat2Runtime"]
        ),
        .executableTarget(
            name: "MessageFormat2TranslateDemo",
            dependencies: ["MessageFormat2Runtime"]
        ),
    ]
)
