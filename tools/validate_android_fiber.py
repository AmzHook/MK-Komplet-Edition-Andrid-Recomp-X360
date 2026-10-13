#!/usr/bin/env python3
"""Optional probes of the compiled ARM64 fiber assembly; no game code runs.

Prepare the pinned native SDK with tools/bootstrap_native.py and build its
rexruntime target before running this script. This verification is deliberately
separate from normal APK builds and Python test discovery.

Optional emulator dependency: Unicorn 2.1.4. A verified Linux x86_64 wheel can
be installed outside the checkout with this command (TLS stays enabled):

  python3 -m pip install --target /workspace/.tools/mk-native/validation/python \
    'https://files.pythonhosted.org/packages/e7/df/ded5e3684c2d7600b30cc8a7530277b8cb36644a1a9d34cade7ebb45604c/unicorn-2.1.4-cp37-abi3-manylinux_2_17_x86_64.manylinux2014_x86_64.whl#sha256=9d6e6dea140560de4ebd8446661f7ef84a357d428c14a3ef09dacd306ec8c239'

Checksum provenance: https://pypi.org/pypi/unicorn/2.1.4/json
Other host platforms require the matching wheel from that verified metadata.

Run: python3 tools/validate_android_fiber.py
The probes exercise register/state preservation, bounds/alignment and initial
entry of the actual NDK-built assembly. Android/Bionic runtime, PAC/BTI hardware
enforcement and game behavior still require separate execution validation.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--native-tools", type=Path,
                        default=Path(os.environ.get("MK_NATIVE_TOOLS", "/workspace/.tools/mk-native")))
    parser.add_argument("--build-dir", type=Path, help="Standalone SDK build containing the compiled fiber object")
    parser.add_argument("--unicorn-python", type=Path, help="Directory containing the optional Unicorn 2.1.4 package")
    parser.add_argument("--output-dir", type=Path, help="Directory for generated assembly/probe evidence")
    args = parser.parse_args()

    root = args.native_tools.resolve()
    emulator_path = args.unicorn_python or root / "validation/python"
    if emulator_path.is_dir():
        sys.path.insert(0, str(emulator_path))
    try:
        import unicorn
        from unicorn import arm64_const as reg
    except ImportError:
        parser.error("Optional Unicorn 2.1.4 is missing; see the pinned install command in --help.")
    if unicorn.__version__ != "2.1.4":
        parser.error("Optional verification requires Unicorn 2.1.4, found " + unicorn.__version__)

    build = (args.build_dir or root / "build-android-arm64").resolve()
    source = build / "src/core/CMakeFiles/rexcore.dir/fiber_android_aarch64.S.o"
    binaries = root / "sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
    objcopy, nm, readelf = (binaries / name for name in ("llvm-objcopy", "llvm-nm", "llvm-readelf"))
    for required in (source, objcopy, nm, readelf):
        if not required.is_file():
            parser.error("Missing native build prerequisite: " + str(required))
    header = subprocess.check_output([str(readelf), "-h", str(source)], text=True)
    if "AArch64" not in header or "REL" not in header:
        parser.error("The fiber object must be an NDK-built AArch64 ELF relocatable object.")

    output = (args.output_dir or root / "validation/fiber-probe").resolve()
    output.mkdir(parents=True, exist_ok=True)
    text_binary = output / "fiber.text.bin"
    # objcopy may rewrite its input unless an output object is explicitly given.
    # Keep the SDK's built object untouched so validation preserves build caches.
    subprocess.run([str(objcopy), "--dump-section", ".text=" + str(text_binary),
                    str(source), str(output / "fiber.object-copy.o")], check=True)
    symbols = {}
    for line in subprocess.check_output([str(nm), "-n", str(source)], text=True).splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[2] in ("rex_fiber_switch_aarch64", "rex_fiber_start_aarch64"):
            symbols[fields[2]] = int(fields[0], 16)
    if len(symbols) != 2:
        parser.error("The compiled fiber object lacks the patched switch/start symbols.")

    code, data, stack_a, stack_b = 0x100000, 0x200000, 0x300000, 0x400000
    save, load = data + 8, data + 0x1008  # 8-aligned contexts, deliberately not 16-aligned.
    stop_a, stop_b = code + 0xF00, code + 0xF04
    register_x = [getattr(reg, "UC_ARM64_REG_X" + str(i)) for i in range(19, 31)]
    register_d = [getattr(reg, "UC_ARM64_REG_D" + str(i)) for i in range(8, 16)]

    def state(prefix, stop, sp, fpcr, fpsr):
        return ([prefix + i for i in range(11)] + [stop, sp]
                + [prefix + 100 + i for i in range(8)] + [fpcr, fpsr])

    def write_state(cpu, values):
        for register, value in zip(register_x, values[:12]):
            cpu.reg_write(register, value)
        cpu.reg_write(reg.UC_ARM64_REG_SP, values[12])
        for register, value in zip(register_d, values[13:21]):
            cpu.reg_write(register, value)
        cpu.reg_write(reg.UC_ARM64_REG_FPCR, values[21])
        cpu.reg_write(reg.UC_ARM64_REG_FPSR, values[22])

    def read_state(cpu):
        return ([cpu.reg_read(r) for r in register_x] + [cpu.reg_read(reg.UC_ARM64_REG_SP)]
                + [cpu.reg_read(r) for r in register_d]
                + [cpu.reg_read(reg.UC_ARM64_REG_FPCR), cpu.reg_read(reg.UC_ARM64_REG_FPSR)])

    def require(condition, message):
        if not condition:
            raise RuntimeError(message)

    cpu = unicorn.Uc(unicorn.UC_ARCH_ARM64, unicorn.UC_MODE_ARM)
    for start, size in ((code, 0x1000), (data, 0x3000), (stack_a, 0x1000), (stack_b, 0x1000)):
        cpu.mem_map(start, size)
    assembly = text_binary.read_bytes()
    require(0 < len(assembly) < 0xF00, "Assembly does not fit the isolated probe code region")
    cpu.mem_write(code, assembly)
    cpu.reg_write(reg.UC_ARM64_REG_CPACR_EL1, 3 << 20)  # Enable FP/ASIMD in the emulator.
    a = state(0x1122334455660000, stop_a, stack_a + 0x1000, 0x00400000, 0x15)
    b = state(0xFFEEDDCCBBAA0000, stop_b, stack_b + 0x1000, 0x00800000, 0x0A)
    cpu.mem_write(data, bytes([0xAB]) * 0x3000)
    cpu.mem_write(load, struct.pack("<23Q", *b))
    write_state(cpu, a)
    cpu.reg_write(reg.UC_ARM64_REG_X18, 0x18181818)
    cpu.reg_write(reg.UC_ARM64_REG_X0, save)
    cpu.reg_write(reg.UC_ARM64_REG_X1, load)
    switch = code + symbols["rex_fiber_switch_aarch64"]
    cpu.emu_start(switch, stop_b, count=100)
    require(list(struct.unpack("<23Q", cpu.mem_read(save, 184))) == a, "Saved caller context differs")
    require(read_state(cpu) == b, "Target registers/SP/FPCR/FPSR were not restored")
    require(cpu.reg_read(reg.UC_ARM64_REG_X18) == 0x18181818, "Switch changed Android's reserved x18")
    require(cpu.mem_read(save - 8, 8) == bytes([0xAB]) * 8, "Save overwrote preceding bytes")
    require(cpu.mem_read(save + 184, 72) == bytes([0xAB]) * 72, "Save exceeded owned context bounds")
    print("PASS: context saves/restores ABI registers, SP and FPCR/FPSR with 8-byte alignment")

    cpu.reg_write(reg.UC_ARM64_REG_X18, 0x19191919)
    cpu.reg_write(reg.UC_ARM64_REG_X0, load)
    cpu.reg_write(reg.UC_ARM64_REG_X1, save)
    cpu.emu_start(switch, stop_a, count=100)
    require(list(struct.unpack("<23Q", cpu.mem_read(load, 184))) == b, "Reverse switch saved incorrect state")
    require(read_state(cpu) == a, "Reverse switch did not restore caller state")
    require(cpu.reg_read(reg.UC_ARM64_REG_X18) == 0x19191919, "Reverse switch restored fiber-owned x18")
    require(cpu.mem_read(load + 184, 72) == bytes([0xAB]) * 72, "Reverse save exceeded context bounds")
    print("PASS: reverse switch restores caller state and keeps x18 owned by the current thread")

    entry_address = code + 0xF80
    entry_start = code + symbols["rex_fiber_start_aarch64"]
    cpu.reg_write(reg.UC_ARM64_REG_X19, entry_address)
    sp = cpu.reg_read(reg.UC_ARM64_REG_SP)
    cpu.emu_start(entry_start, entry_address, count=10)
    require(cpu.reg_read(reg.UC_ARM64_REG_LR) == entry_start + 8, "Initial entry has an invalid return address")
    require(cpu.reg_read(reg.UC_ARM64_REG_SP) == sp and sp % 16 == 0, "Initial entry stack is misaligned")
    require(cpu.reg_read(reg.UC_ARM64_REG_X18) == 0x19191919, "Initial entry changed x18")
    print("PASS: initial entry calls the trampoline with a valid LR and 16-byte aligned SP")

    report = {
        "engine": "Unicorn 2.1.4", "probes_passed": 3,
        "object": str(source), "object_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "text_sha256": hashlib.sha256(assembly).hexdigest(),
        "game_code_executed": False, "android_device_runtime_tested": False,
        "checks": ["x19-x30", "sp", "d8-d15", "fpcr", "fpsr", "x18_untouched",
                   "storage_bounds", "8byte_context_alignment", "initial_call_frame"],
    }
    report_path = output / "fiber-asm-validation.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    print("Evidence:", report_path)


if __name__ == "__main__":
    main()
