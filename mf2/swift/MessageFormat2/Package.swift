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
            name: "MessageFormat2",
            targets: ["MessageFormat2"]
        ),
    ],
    targets: [
        .target(name: "MessageFormat2"),
        .executableTarget(
            name: "MessageFormat2Conformance",
            dependencies: ["MessageFormat2"]
        ),
        .executableTarget(
            name: "MessageFormat2TranslateDemo",
            dependencies: ["MessageFormat2"]
        ),
    ]
)
