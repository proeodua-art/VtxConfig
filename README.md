# VtxConfig

Offline Java 17+ Swing application for Betaflight VTX configuration.

## Build

Linux/macOS development:

```bash
javac -d out src/VtxApp.java
jar --create --file out/VtxConfig.jar --main-class VtxApp -C out .
```

For a self-contained application image on the current OS run `build-linux.sh` on Linux.

For Windows MSI, run `build-windows.ps1` **on Windows** with JDK 17+ and WiX 3.0+ available to `jpackage`. `jpackage` creates native packages for the platform on which it runs; Windows MSI cannot be produced by the Linux build host.

## Betaflight notes

- The generated VTX table includes `powerlevels`, `powervalues`, and `powerlabels`.
- The E band uses the current documented common frequencies: 5705, 5685, 5665, 5645, 5885, 5905, 5925, 5945 MHz.
- `CUSTOM` is used for the generated bands. Verify that this matches the VTX hardware; `FACTORY` and `CUSTOM` have different semantics in Betaflight.
- SmartAudio 2.1 power values are model-specific. The UI marks its example table as an example; verify with `vtx_info` and the VTX manufacturer's data before use.
- The optional legacy `serial` line is for Betaflight 2025.12 and earlier. From 2026.12, use `set vtx_uart = UARTn`; `serial` is read-only.
- `Send to FC` is implemented for Windows COM ports and sends `#`, the generated CLI, and either `save` or `exit noreboot`.

## Data

Configuration data is stored under the per-user application configuration directory, not beside the installed executable.
