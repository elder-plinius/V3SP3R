# Flipper RPC schemas

These files are pinned from [`flipperdevices/flipperzero-protobuf`](https://github.com/flipperdevices/flipperzero-protobuf) at commit:

`1c84fa48919cbb71d1cc65236fc0ee36740e24c6`

The generated Swift sources use Apple SwiftProtobuf `1.32.0` (`c6fe6442e6a64250495669325044052e113e990c`) and are committed under `ios/Vesper/Generated` so normal Xcode builds do not need a globally-installed generator.

Run `ios/Scripts/regenerate-protos.sh /path/to/protoc-gen-swift` after updating the pinned schemas. Review the generated diff and run the iOS test suite before committing it.
