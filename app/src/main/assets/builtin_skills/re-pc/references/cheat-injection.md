## Injection and Cheat Code

Inject cheats with surgical precision using these elite methods.

### Core Techniques

- **Classic LoadLibrary Injection**:
  ```cpp
  HANDLE hProc = OpenProcess(PROCESS_ALL_ACCESS, FALSE, pid);
  LPVOID addr = VirtualAllocEx(hProc, NULL, strlen(dllPath)+1, MEM_COMMIT, PAGE_READWRITE);
  WriteProcessMemory(hProc, addr, dllPath, strlen(dllPath)+1, NULL);
  CreateRemoteThread(hProc, NULL, 0, (LPTHREAD_START_ROUTINE)LoadLibraryA, addr, 0, NULL);
  ```

- **Manual Mapping**: Bypass detection with stealth injection (e.g., GH Injector).
- **Inline Hooks**:
  ```asm
  Original:
  MOV EAX, [EBP+8]
  CALL GameFunction

  Hooked:
  JMP HookFunction
  NOP
  ```

- **VMT Hooking**:
  ```cpp
  DWORD* vTable = *(DWORD**)player;
  DWORD original = vTable[5];
  vTable[5] = (DWORD)&MyFunction;
  ```

- **.text Cave Injection**: Hide code in unused executable sections.
- **CreateRemoteThread**: Inject into suspended processes silently.
- **IAT/EAT Patching**: Redirect import/export tables to custom functions.
- **Hook Direct3D EndScene()/Present()**: Render ESP overlays.
- **Shellcode in Heap**: Inject small stubs for minimal footprint.
- **SetWindowsHookEx**: Capture keyboard/mouse input globally.

---

### Stealth Injection

- **Process Hollowing**: Replace `svchost.exe` with game binary using `NtUnmapViewOfSection + ZwMapViewOfSection`.
- **Vectored Exception Handling (VEH)**: Hijack execution flow via `AddVectoredExceptionHandler`.
- **Reflective DLL Injection**: Load DLLs from memory without touching disk.

---

### Advanced Injection Strategies

| Technique             | Method                              | Use Case                  |
|-----------------------|--------------------------------------|----------------------------|
| .text Cave Injection  | Inject in unused code section        | High stealth               |
| VEH Hook              | Trigger via exception handler        | Reflective injection       |
| TLS Callback          | Run code before `main()` in PE       | Pre-initialization         |
| IAT Patching          | Redirect imports (e.g., `MessageBoxA`) | Function hijacking       |
| Discord Overlay Hijack | DLL sideload via overlay            | Bypass anti-cheat          |

- **eBPF Hooking (Linux)**: Attach probes to kernel syscalls for stealth.
- **PTrace Injection (Android)**: Modify running code using `PTRACE_POKETEXT`.

---

## DRM and Obfuscation Bypass

Crack protections with these advanced techniques.

### Core Techniques

- Bypass Denuvo: Dump memory mid-run with x64dbg.
- Locate OEP: Trace back to original entry point.
- Rebuild PEs: Use Scylla / PE-bear to fix dumped binaries.
- Patch Decryption Loops: Remove XOR routines from loaders.
- Disable CRC Checks: Patch integrity verification.
- Locate License Checks: Cross-reference key strings in IDA.
- Inject at Handoff: Hook stub-decryption transitions.
- Devirtualize: Unpack VMProtect / Themida.
- Hook `NtOpenFile`: Intercept license queries via Frida.

---

### Denuvo Cracking

- **Memory Dumping**: Use ScyllaHide to evade debugger checks and dump decrypted `.text` sections.
- **Emulation**: Reconstruct VM handlers using Qiling Framework.

---

### Advanced Techniques

- VMProtect 3.x Unpacking: Decode x86 opcodes with Triton.
- ASLR Bypasses: Patch static memory for reliable exploitation.

---

## Shellcode Engineering

Craft stealthy payloads with these methods.

### Core Techniques

- ESP Overlays: Execute via render function hooks.
- Polymorphic XOR: Compress/obfuscate shellcode payloads.
- Overflow Triggers: Inject via savegame or file parsers.
- Config-File Loading: Store payloads externally.
- OCR-Based ESP: Use screen capture + OpenCV, no injection.
- Heap Spray: Execute through Lua / JS scripting engines.
- Alphanumeric Payloads: For character-restricted exploits.
- TLS Callbacks: Run before `main()` in PE headers.
- Custom Syscalls: Avoid usermode detection.

---

### Advanced Engineering

- **SELF**: Staged ELF Loader with LZMA compression and `mprotect` stub.
- **Thread Hijacking**:
  ```c
  NtSuspendThread(hThread);
  WriteProcessMemory(...); // overwrite RIP
  ```
- **ROP Bootstrapping**: Launch shellcode via gadgets.

---
## DRM Loader Staging

Modern DRM systems deploy multi-stage loaders, packing and obfuscating payloads using VM-based encryption, anti-debugging logic, and staged virtual machine handlers. Breaking through these layers is essential for:

- Restoring clean .text sections  
- Analyzing game logic behind anti-tamper wrappers  
- Reconstructing protected functions for cheat injection  
- Defeating signature checks and telemetry sinks

### Key Concepts

| Concept | Description |
|--------|-------------|
| Loader staging | Multiple layers of unpacking: stub → loader → VM |
| Virtualization | Code translated into custom bytecode and interpreted |
| Mutation engines | Obfuscate instructions and flow via polymorphism |
| Anti-dump | Prevent dumping memory with CRCs, active page clearing |
| Loader chain detection | Uncover multi-executable chains embedded in final binary |

### Reverse Engineering Process (Staged DRMs)

### 1. Detect the Staging Behavior

- High entropy in .text, .vmp0, or .code → indicates encryption  
- Stub code at OEP (original entry point) → `jmp short _loadnext`  
- Long sleep / timing checks → anti-debug  

**Use:**
```sh
binwalk --entropy binary.exe
```

Tools: PEiD / Detect It Easy

### 2. Locate the Real Entry Point

Staged loaders often call:
```asm
CALL DecryptAndExecute
JMP EAX
```

Trace VirtualAlloc → memcpy → CreateThread or jmp rax

**Watch for:**

- NtProtectVirtualMemory with RWX permissions  
- memcpy into a shell region  
- Encrypted VM blob → then mapped and run

### 3. Trace Loader Flow with x64dbg

Place breakpoints:
```x64dbg
bp kernel32!VirtualAlloc
bp kernel32!CreateThread
```

Then dump memory once second-stage loader appears.

### 4. VMProtect Loader Internals

| Stage | Purpose |
|-------|---------|
| Stage 0 | PE stub (launches decryptor) |
| Stage 1 | Loader stub (decrypts VM blob) |
| Stage 2 | Encrypted VM bytecode in .vmp0 |
| Stage 3 | Custom VM interprets protected funcs |

**Signs of VMProtect:**

- .vmp0, .vmp1, .vmp2 sections  
- `MOV EAX, VM_OPCODE_TABLE`  
- High-entropy embedded dispatch loop  

**Tools:** VMPDump, x64dbg + Scylla + VMProtectTrace

### 5. VM Handler Identification

Dispatch logic:
```asm
movzx eax, byte ptr [ecx]    ; opcode fetch
call [OpcodeHandler + eax*4] ; handler dispatch
```

Use Unicorn engine:
```python
mu.mem_write(vm_addr, vm_code)
mu.emu_start(vm_addr, vm_addr + len(vm_code))
```

### Nested Loader Unpacking

- Multiple compressed regions (LZ4, LZO, LZSS)  
- XOR-encrypted memory blocks  
- Anti-VM or anti-dump logic

Use: Scylla, PE-sieve, Cheat Engine

### Anti-Debug/Anti-Dump Bypasses

| Defense Mechanism | Bypass Technique |
|-------------------|------------------|
| Hardware breakpoint check | Patch IsDebuggerPresent, NtQueryInfoProcess |
| CRC32 page check | Patch CRC logic with RET or NOPs |
| Page clearing on dump | Dump post-RWX and force page copy |
| VEH-based obfuscation | Remove AddVectoredHandler entries |

**Tools:** ScyllaHide, TitanHide, PE-sieve

### Manual Dump and Rebuild

```python
import frida

def on_message(msg, data):
    if msg["type"] == "send":
        print("[*]", msg["payload"])

session = frida.attach("target.exe")

script = session.create_script("""
Interceptor.attach(Module.getExportByName(null, "VirtualAlloc"), {
  onLeave: function (retval) {
    send("Alloc at: " + retval);
  }
});
""")
script.on('message', on_message)
script.load()
```

### Denuvo Specific Staging

- .text0 → Loader stub  
- .text1 → Encrypted ELF or PE blob  
- .bind, .elfhash, .denuvo sections  

**Reverse:**

- Hook `NtQueryVirtualMemory`, `NtReadVirtualMemory`  
- Look for RDTSC anti-debug timings  
- Use Cheat Engine Snapshot Compare

### Common Loader Signatures

| Loader Stage | Signature / API | Description |
|--------------|------------------|-------------|
| Stub loader | jmp [rax], entropy | Entry obfuscator |
| Memory decryptor | RtlDecompressBuffer, VirtualProtect | Payload unpacker |
| VM dispatcher | mov al, [ecx], call [eax*4] | Custom VM handler switch |
| Anti-debug | RDTSC, CPUID, int 3 | Timing + breakpoint checks |

### DRM Loader Fuzzing / Mutation

Use LIEF to:

- Modify PE headers, section alignments  
- Patch entry point or stub region  

Combine with AFL++ to fuzz staged binaries.

### DRM Tooling Ecosystem

| Tool | Purpose |
|------|---------|
| x64dbg + Scylla | Manual unpack, IAT fix |
| PE-sieve | Detect memory-mapped unpacked modules |
| VMProtectDump | Dump runtime-decrypted VM code |
| TitanHide | Hide debugger from anti-debug checks |
| IDA Pro + HexRays | Advanced disasm and pseudocode |
| LIEF | Programmatic PE patching |

---

## VM-Level Cheats using EPT, NPT, and Bluepill

By using hardware-assisted virtualization, we can intercept and manipulate game memory without directly modifying it — enabling powerful cheat capabilities while evading detection by anti-cheat systems like BattleEye, Vanguard, or EAC.

This class of cheats resides below the kernel, using hypervisors and page table remapping (EPT/NPT) to view and/or manipulate memory from another ring (Ring -1) — below Ring 0.

### Core Concepts

| Term     | Description |
|----------|-------------|
| EPT (Intel) | Extended Page Tables — allows second-level address translation in VM |
| NPT (AMD) | Nested Page Tables — same purpose as EPT but for AMD-V |
| Bluepill | A rootkit or hypervisor that silently loads under the host OS |
| Ring -1 | Privilege level used by hypervisors (below kernel Ring 0) |
| VMX / SVM | Intel and AMD virtualization instructions (vmxon, vmexit, etc.) |
| VMM | Virtual Machine Monitor (a.k.a. hypervisor, either custom or KVM/Hyper-V) |

### Use Cases in Game Hacking

- External ESP Overlays
- Read-Protected Pages
- Undetectable Memory View
- Runtime Memory Redirection
- Full Memory Timeline

### How It Works: EPT Memory View (Intel)

```
+---------------------+       +-----------------------------+
| Guest Virtual Addr  | --->  | Guest Physical Addr (GPA)   |
+---------------------+       +-----------------------------+
                                   ↓
                           +---------------------+
                           | Host Physical Addr   |
                           +---------------------+
```

### Techniques

### 1. Custom Hypervisor (KVM, Bare-metal, SimpleVisor)

- Sets EPT/NPT permissions
- Logs reads/writes
- Triggers VMExit

Projects: SimpleVisor, Hvpp, LibVMI

### 2. Hyper-V Based External ESP

- Run game in Hyper-V
- Read memory from host using LibVMI

### 3. Memory Redirection via EPT Hooks

```c
// EPT hook concept
setup_ept_hook(target_gpa, callback_on_readwrite);
```

### 4. Bluepill Hypervisor Injection

- vmxon to activate VMX root mode
- Live patching without drivers

### Anti-Detection Advantages

| Feature | Traditional Cheat | VM-Level Cheat |
|---------|-------------------|----------------|
| Requires driver | ✅ | ❌ |
| Visible to AV | ✅ | ❌ |
| Touches game RAM | ✅ | ❌ |
| Bypasses PatchGuard | ❌ | ✅ |
| Hooks detected | ✅ | ❌ |

### Advanced Applications

- Shadow Memory
- Page Fault ESP
- Instruction Hooks
- DMA Isolation

### Tooling Ecosystem

| Tool | Purpose |
|------|---------|
| SimpleVisor | EPT hypervisor |
| hvpp | VT-x engine |
| LibVMI | VM memory introspection |
| DRAKVUF | Xen-based tracer |
| HyperDbg | VM debugger |
| Bareflank | C++ hypervisor framework |

### Real-World Exploit Flow: Silent ESP via LibVMI

```bash
