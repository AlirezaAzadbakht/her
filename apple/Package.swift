// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "HerApple",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "HerCore", targets: ["HerCore"]),
               .executable(name: "Her", targets: ["HerApp"])],
    dependencies: [.package(url: "https://github.com/google/GoogleSignIn-iOS.git", exact: "9.0.0")],
    targets: [
        .target(name: "HerCore"),
        .executableTarget(name: "HerApp", dependencies: ["HerCore", .product(name: "GoogleSignIn", package: "GoogleSignIn-iOS")]),
        .testTarget(name: "HerCoreTests", dependencies: ["HerCore"])
    ]
)
