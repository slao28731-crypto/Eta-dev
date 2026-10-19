## Recon and Static Analysis

Unravel game internals with these elite static analysis techniques.

### Core Techniques

- Run `strings`, `binwalk`, `hexdump` on binaries: Extract plaintext, embedded files, and hex patterns for initial insights.
- Reverse `.exe` / `.dll` with **Ghidra**, **IDA**, or **Binary Ninja**: Decompile to pseudocode or assembly; leverage **FLIRT** signatures for known libraries (e.g., Unity, Unreal SDKs).
- Map `main()`, `WinMain()`, or game loops: Trace entry points and core logic flows in disassemblers.
- Extract debug symbols from `.pdb` / `.dbg` files: Recover function names, variables, and call stacks using DIA SDK.
- Analyze sections (`.text`, `.rdata`, `.data`, `.reloc`, `.bss`): Identify code, strings, globals, relocations, and uninitialized data.
- Identify calls to `GetAsyncKeyState`, `memcmp`, `strstr`: Locate input handling and string comparison routines.
- Search internal function names via `strings` / RTTI: Exploit runtime type info or plaintext leaks to map logic.
- Enumerate imports with `rabin2 -i` or **LIEF**: List DLL dependencies and hooked APIs.
- Check linked libraries (DirectX, Mono, Vulkan): Detect frameworks for rendering, scripting, or physics.

---

### Deep Dive and Expansion

Static analysis is often underutilized—maximize it with advanced correlation and metadata extraction.

#### Binary Forensics

- Use `radare2`:
  ```sh
  aaaa   # Auto-analysis  
  izz    # Extract strings  
  iS     # Check section entropy
  ```

- Entropy Mapping:
  - Identify packed/encrypted regions using `binwalk -E` or **EntropyGUI**
  - High entropy (>7.0) suggests obfuscation

---

#### Cross-Platform Analysis

- **Mach-O (macOS)**:
  - Use `otool -lV` for load commands
  - Use `jtool2` for Objective-C metadata

- **ELF (Linux)**:
  - Run `readelf -Ws` for dynamic symbols
  - Use `patchelf` to modify interpreters

---

#### Obfuscation Breakers

- **Symbol Recovery**: Parse stripped `.pdb` files using DIA SDK
- **RTTI Exploitation**: Rebuild C++ vtables in IDA using `RTTI::CompleteObjectLocator`
- **Code Cave Detection**:
  ```python
  for seg in Segments():
      if SegName(seg) == ".text":
          for func in Functions(seg, SegEnd(seg)):
              size = GetFunctionAttr(func, FUNCATTR_END) - func
              if size > 5000:
                  print("Potential cave at:", hex(func), "Size:", size)
  ```

- **CRC Check & Anti-Tamper Tracing**:
  - Look for `mov eax, ds:CRC_TABLE`, `xor ecx, [ptr]`, etc.

- **PDB Symbol Leeching**:
  - Use Microsoft Symbol Servers (`symsrv`) to pull symbols from related builds

- **Embedded Scripting Engine Detection**:
  - Look for: `PyRun_SimpleString`, `lua_pcall`, `duk_eval_string`

---

#### Real-World Metadata Recon

- **PE Authenticode Signature Diffing**:
  ```sh
  sigcheck.exe -q -m  
  osslsigncode
  ```

- **Language / Compiler Fingerprinting**:
  - Use `binlex`, `lief`, or `retdec`

| Pattern                | Origin                 |
|------------------------|------------------------|
| SEH frames             | MSVC (Visual Studio)   |
| `il2cpp::vm::` calls   | Unity IL2CPP           |
| `UFunction::ProcessEvent` | Unreal Engine       |

---

#### Ghidra Headless Automation

```bash
./analyzeHeadless project_dir project_name -import target.exe -postScript ExtractStrings.java -deleteProject
```

---

### Obfuscation and Packing Detection Matrix

| Obfuscation Technique        | Detection Method                      | Tool(s)                         |
|-----------------------------|----------------------------------------|----------------------------------|
| UPX / Common Packers        | Strings entropy, section size          | `binwalk`, `die`, `upx -t`       |
| VMProtect / Themida         | `.text` entropy > 7.3, jmp chains       | `PEiD`, `Detect It Easy`         |
| Unity IL2CPP + Metadata     | `global-metadata.dat` presence         | `Il2CppDumper`, `IDA Pro`        |
| Custom XOR / Rijndael       | High-entropy strings, XOR loops        | `radare2`, `capstone`, `Unicorn` |
| Lua Bytecode / JIT          | `1B 4C 75 61` (Lua header)             | `luadec`, `lua-dis`              |

---

### Game-Specific Recon Signatures

| Engine       | Recon Target                        | Indicator / Signature                              |
|--------------|--------------------------------------|-----------------------------------------------------|
| Unity (Mono) | `Assembly-CSharp.dll`, MonoBehaviour | Public classes, IL2CPP strings                      |
| Unreal (UE)  | `UObject::GObjects`, `GNames`        | `48 8B 05 ?? ?? ?? ?? 48 8B 0C C8`                  |
| CryEngine    | `CrySystem.dll`, `CryEntitySystem.dll` | `CEntity::Update()` in IDA                       |
| Source       | `client.dll`, `engine.dll`           | `CreateMove`, `PaintTraverse`                      |
| GameMaker    | `*.yy`, `*.yyp`, `GameMakerUI.dll`   | `InitGameObject`, `ObjectSetLayer`                 |

---

### Asset Recon (Deep Reverse)

- **Unity**:
  - Use `AssetRipper` or `AssetStudio` to extract textures, classes
  - Analyze `global-metadata.dat`, `libil2cpp.so` for IL2CPP mappings

- **Unreal Engine**:
  - Dump `GObjects`, `GNames`, `UClass` hierarchy using CE Table or SDK Generator
  - Patch `Engine.ini`:
    ```ini
    [Core.Log]
    LogNet=Verbose
    LogNetTraffic=VeryVerbose
    ```

- **Browser/WebGL Titles**:
  - Use `wasm-decompile`, `wasm2wat`, Chrome DevTools
  - Hook `eval`, `Function`, `WebAssembly.instantiateStreaming`

---

### Advanced Techniques

- **Capstone + Unicorn**: Emulate decryption logic (e.g., XOR loops)
- **LLVM IR Analysis**: Use RetDec to lift binaries to LLVM IR
- **Custom Signatures**: Build FLIRT sigs for FMOD, PhysX, etc. using Ghidra

---
## Dynamic Memory Analysis

Master real-time memory manipulation with these professional-grade methods.

### Core Techniques

- Attach Cheat Engine, x64dbg, or Frida: Monitor live processes with breakpoints and value scans.
- Scan/Freeze In-Memory Values: Lock health, ammo, or gold by finding and freezing addresses.
- Trace "What Writes to Address": Locate opcodes modifying key variables (e.g., player stats).
- Heap Spray Tracing: Monitor allocations during crafting or spawning for overflow targets.
- Dynamic Import Resolution: Hook LoadLibrary/GetProcAddress to log runtime DLL calls.
- Hook/Detour with Frida: Inject custom logic into game functions dynamically.
- Use ReClass.NET: Reverse-engineer memory structures (e.g., player class pointers).
- Hook DirectX/OpenGL: Overlay ESP/aimbot visuals by intercepting render calls.
- Trace Memory Maps: Use /proc/<pid>/maps (Linux) or VirtualQueryEx (Windows) to chart layouts.
- Monitor Telemetry: Sniff heartbeat timers or uploads with Process Monitor/Wireshark.

---

### Deep Dive

Flip bits in real-time with these advanced tactics.

### Next-Level Techniques

- Time Travel Debugging (TTD): Record execution with WinDbg Preview TTD, rewind to trace variable origins.
- Heap Feng Shui: Force predictable heap layouts with controlled allocations (e.g., spray 0x1000-byte objects).
- Frida Stalker:
  ```javascript
  Stalker.follow({
    events: { compile: true },
    onReceive: function (blocks) { console.log(blocks); }
  });
  ```
- DirectX/OpenGL Hooking:
  - RenderDoc: Capture frames to reverse shaders.
  - VTable Hooking: Swap IDXGISwapChain::Present for ESP overlays.
- Kernel-Mode Monitoring: Use Intel Processor Trace (PT) via perf (Linux) or WinDbg kernel debugging.

---

### Advanced Live Tactics

- Frida Heap Tracker:
  ```javascript
  Interceptor.attach(Module.getExportByName(null, "malloc"), {
    onEnter: function (args) {
      this.size = args[0].toInt32();
    },
    onLeave: function (retval) {
      if (this.size == 0x500) {
        console.log("[*] Large allocation at: " + retval);
      }
    }
  });
  ```
- Shadowing Game Logic: Identify duplicate structs (e.g., player_data vs. player_shadow) in ReClass.NET to exploit state management.
- Dynamic Function Pointer Dispatch:
  ```javascript
  var base = ptr("0x400000");
  Memory.scan(base, 0x100000, "?? ?? ?? ?? ?? ?? ?? ??", {
    onMatch: function (address, size) {
      if (!address.readPointer().isNull()) {
        console.log("VTable candidate at:", address);
      }
    }
  });
  ```
- Continuous Memory Map Correlation: Track allocation deltas with VirtualQueryEx (Windows) or diff /proc/<pid>/maps (Linux).
- Snapshot-Diffing: Take memory dumps at different states and compare with pymem, pydiff, or Rust scanners.
- Memory Breakpoint Watch:
  - Cheat Engine: Right-click → "Find out what writes to this address"
  - x64dbg:
    ```bash
    bp access mem 0xDEADBEEF size 4 r/w
    ```

---

### Advanced Techniques

- Intel PIN: Instruction-level tracing for fine-grained analysis.
- Memory Allocator Hooks: Intercept malloc/HeapAlloc to track allocations.
- Custom Scanners: Build memory scanners in Rust for cross-platform efficiency.

---

### Heap Spraying
```csharp
List<GameObject> spray = new List<GameObject>();
for (int i = 0; i < 10000; i++) {
  spray.Add(new GameObject("HeapObject" + i));
}
```

### Frida: Hooking malloc and free
```js
Interceptor.attach(Module.getExportByName(null, 'malloc'), {
  onEnter(args) {
    this.size = args[0].toInt32();
  },
  onLeave(retval) {
    if (this.size > 1024) {
      console.log(`[+] Allocated: ${this.size} bytes at ${retval}`);
    }
  }
});
```

### Live Allocation Tracker
```js
Interceptor.attach(Module.getExportByName(null, "operator new"), {
  onEnter: function (args) {
    this.sz = args[0].toInt32();
  },
  onLeave: function (retval) {
    console.log("[+] new() size:", this.sz, " -> ", retval);
  }
});
```

### Memory Map Diffing
Linux:
```sh
cat /proc/<pid>/maps
```

Windows:
```python
VirtualQueryEx(hProc, address)
```

### Dynamic Function Discovery via Frida
```js
Module.enumerateRanges('r-x').forEach(range => {
  Memory.scan(range.base, range.size, '55 8B EC', {
    onMatch(addr) {
      console.log("[*] Function prologue at:", addr);
    }
  });
});
```

---


---

### Recommended Tools

| Purpose              | Tool                        |
|----------------------|-----------------------------|
| Heap Tracing         | Frida, Valgrind (Linux)     |
| Structure Reversing  | ReClass.NET                 |
| Frame Capture        | RenderDoc, PIX              |
| Runtime Instrumentation | Frida, Intel PIN         |
| Live Scanning        | pymem, Rust+WinAPI          |


---

---
## Advanced Cheat Engine Usage

Cheat Engine (CE) is a powerful reverse engineering and memory editing tool, far beyond just scanning for health or ammo. Below is a modular breakdown to push CE into red team and CTF-grade use.

---

### Tools Needed

- Cheat Engine (latest build)
- Custom driver (signed or unsigned)
- Windows x64 target (Unity, Unreal, Mono, etc.)
- Optional: Frida / x64dbg / ReClass.NET

---

### 1. Pointer Path Tracing (Multilevel Pointer Maps)

In modern games, static addresses don’t last — you must trace pointers.

**Manual Pointer Walk**:

```
[game.exe+0x02F41B90] → 0xDEADBEEF → +0x10 → Health
```

Steps:

- Scan for health
- Right-click → “What accesses this address”
- Use “Pointer scan” → “Find path to value”
- Reboot and validate

**Auto Pointer Lookup via Lua**:

```lua
local base = readPointer("game.exe+0x02F41B90")
local health = readInteger(base + 0x10)
print("Health:", health)
```

---

### 2. Code Injection w/ Auto Assembler

Patch game logic or build trainers.

**Example: Health Freeze**:

```asm
[ENABLE]
alloc(newmem,2048)
label(return)

newmem:
  mov [eax+10],#999
  jmp return

"game.exe"+123456:
  jmp newmem
return:
```

Bonus:
- Use `jmp short` vs `jmp near` based on distance (5-byte near patch)

---

### AOBScan for ASLR-Busting

Use signatures to find injection sites dynamically.

```asm
[ENABLE]
aobscanmodule(INJECT_AOB,game.exe,89 54 24 10 8B 45 ??)
alloc(newmem,2048,"game.exe")
label(return)

newmem:
  nop
  nop
  jmp return

INJECT_AOB:
  jmp newmem
return:
```

Good AOB tips:
- Unique, short patterns
- Avoid excessive wildcards
- Grab from IDA/CE memory viewer

---

### CE Mono Framework (Unity Games)

Interact directly with Mono-based Unity games.

Steps:

- Attach → Mono → Activate Mono Features
- Use “Dissect Mono” to inspect class/methods

🔧 Hook a Unity Method (Lua):

```lua
local method = mono_findMethod("Assembly-CSharp", "Player", "TakeDamage")
print("TakeDamage at:", string.format("0x%X", method.address))
```

---

### Lua Scripting for Runtime Cheats

CE’s Lua API enables real-time memory editing and trainers.

**F6 Hotkey to Refill Ammo:**

```lua
function refillAmmo()
  writeInteger("[game.exe+0x1A2B3C4]", 999)
end

createHotkey(refillAmmo, VK_F6)
```

Add via Table → Show Cheat Table Lua Script

---

### Anti-AntiCheat Stealth Tactics

CE is detectable — use these strategies:

#### Signature Evasion:

- Rename executable
- Hex-edit PE headers
- Strip metadata/version info

#### PEB Unlinking:

```lua
dbk_writesIgnoreWriteProtection(true)
```

#### Use Stealth Edit Plugin:

- Avoid global hooks
- Inline memory edits

#### Driver Tricks:

- Custom `dbk64.sys`
- Load unsigned via KDMapper or Test Mode

---

### Code Cave Injection

Patch unused memory space for full logic.

Steps:

- Search for `00 00 00 00` in `.text` or `.data`
- Inject your logic
- JMP from game code to cave

```lua
alloc(cave, 512, "game.exe+0x123456")
```

---

### CE and Frida Hybrid Debugging

Combine CE scanning + Frida hooks:

**Use CE for:**

- Live struct discovery
- Memory validation

**Use Frida for:**

- Internal function hooking
- Argument manipulation

```js
Interceptor.attach(ptr("0xDEADBEEF"), {
  onEnter(args) {
    args[0] = ptr(999);
  }
});
```

---
## Memory Forensics Evasion

### Anti-Memory Dump Techniques

```cpp
#include <Windows.h>
#include <winternl.h>

class AntiDumpTechniques {
public:
    // Erase PE header from memory
    static void ErasePEHeader() {
        HMODULE hModule = GetModuleHandle(NULL);
        PIMAGE_DOS_HEADER pDosHeader = (PIMAGE_DOS_HEADER)hModule;
        PIMAGE_NT_HEADERS pNTHeaders = (PIMAGE_NT_HEADERS)((BYTE*)hModule + pDosHeader->e_lfanew);
        
        DWORD oldProtect;
        VirtualProtect(hModule, pNTHeaders->OptionalHeader.SizeOfHeaders, PAGE_READWRITE, &oldProtect);
        
        // Zero out PE header
        memset(hModule, 0, pNTHeaders->OptionalHeader.SizeOfHeaders);
        
        VirtualProtect(hModule, pNTHeaders->OptionalHeader.SizeOfHeaders, PAGE_READONLY, &oldProtect);
    }
    
    // Hide memory regions from tools
    static void HideMemoryRegions() {
        MEMORY_BASIC_INFORMATION mbi;
        PBYTE address = NULL;
        
        while (VirtualQuery(address, &mbi, sizeof(mbi))) {
            if (mbi.State == MEM_COMMIT && mbi.Type == MEM_PRIVATE) {
                // Change page protection to hide from scanners
                DWORD oldProtect;
                VirtualProtect(mbi.BaseAddress, mbi.RegionSize, PAGE_NOACCESS, &oldProtect);
                
                // Perform operations
                // ...
                
                // Restore protection
                VirtualProtect(mbi.BaseAddress, mbi.RegionSize, oldProtect, &oldProtect);
            }
            
            address += mbi.RegionSize;
        }
    }
    
    // Detect memory scanning tools
    static bool DetectMemoryScanner() {
        // Check for known scanner process names
        const wchar_t* scanners[] = {
            L"cheatengine-x86_64.exe",
            L"ollydbg.exe",
            L"x64dbg.exe",
            L"processhacker.exe"
        };
        
        HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (hSnapshot == INVALID_HANDLE_VALUE) return false;
        
        PROCESSENTRY32W pe32;
        pe32.dwSize = sizeof(PROCESSENTRY32W);
        
        if (Process32FirstW(hSnapshot, &pe32)) {
            do {
                for (const auto& scanner : scanners) {
                    if (_wcsicmp(pe32.szExeFile, scanner) == 0) {
                        CloseHandle(hSnapshot);
                        return true;
                    }
                }
            } while (Process32NextW(hSnapshot, &pe32));
        }
        
        CloseHandle(hSnapshot);
        return false;
    }
    
    // Encrypt sensitive data in memory
    static void EncryptMemoryRegion(void* data, size_t size, DWORD key) {
        DWORD* ptr = (DWORD*)data;
        size_t dwords = size / sizeof(DWORD);
        
        for (size_t i = 0; i < dwords; i++) {
            ptr[i] ^= key;
        }
    }
    
    // Guard pages to detect scanning
    static void SetupGuardPages(void* address, size_t size) {
        DWORD oldProtect;
        VirtualProtect(address, size, PAGE_EXECUTE_READ | PAGE_GUARD, &oldProtect);
    }
};

// Anti-dump guard
class MemoryGuard {
private:
    static LONG CALLBACK VectoredHandler(EXCEPTION_POINTERS* pExceptionInfo) {
        if (pExceptionInfo->ExceptionRecord->ExceptionCode == STATUS_GUARD_PAGE_VIOLATION) {
            // Memory accessed by scanner detected
            ExitProcess(0);
        }
        
        return EXCEPTION_CONTINUE_SEARCH;
    }
    
public:
    static void Initialize() {
        AddVectoredExceptionHandler(1, VectoredHandler);
        
        // Erase headers
        AntiDumpTechniques::ErasePEHeader();
        
        // Check for scanners
        if (AntiDumpTechniques::DetectMemoryScanner()) {
            ExitProcess(0);
        }
    }
};
```

---

