// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "HerApple",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "HerCore", targets: ["HerCore"]),
               .executable(name: "Her", targets: ["HerApp"])],
    targets: [
        .target(name: "HerCore"),
        .executableTarget(name: "HerApp", dependencies: ["HerCore"]),
        .testTarget(name: "HerCoreTests", dependencies: ["HerCore"])
    ]
)
