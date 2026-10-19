## Modern Graphics API Hooking (DirectX 12/13, Vulkan, Metal)

Modern graphics APIs use command-based rendering with minimal driver overhead, requiring different hooking approaches than DX9/11.

### DirectX 12 Command List Hooking

DX12 uses command lists recorded on CPU and submitted to GPU, making traditional Present() hooks insufficient.

#### Architecture

```
ID3D12Device → ID3D12CommandQueue → ID3D12CommandList → ExecuteCommandLists() → Present()
```

#### Hooking ExecuteCommandLists

```cpp
#include <d3d12.h>
#include <dxgi1_4.h>

typedef void (STDMETHODCALLTYPE* ExecuteCommandLists_t)(ID3D12CommandQueue*, UINT, ID3D12CommandList* const*);
ExecuteCommandLists_t oExecuteCommandLists = nullptr;

void STDMETHODCALLTYPE hkExecuteCommandLists(ID3D12CommandQueue* pCommandQueue, UINT NumCommandLists, ID3D12CommandList* const* ppCommandLists) {
    for (UINT i = 0; i < NumCommandLists; i++) {
        ID3D12GraphicsCommandList* pCommandList = nullptr;
        ppCommandLists[i]->QueryInterface(IID_PPV_ARGS(&pCommandList));
        
        if (pCommandList) {
            // Inject our own commands (ESP rendering)
            InjectESPCommands(pCommandList);
            pCommandList->Release();
        }
    }
    
    return oExecuteCommandLists(pCommandQueue, NumCommandLists, ppCommandLists);
}

void InjectESPCommands(ID3D12GraphicsCommandList* pCommandList) {
    // Set ESP pipeline state
    pCommandList->SetPipelineState(g_pESPPipelineState);
    pCommandList->SetGraphicsRootSignature(g_pESPRootSignature);
    
    // Set descriptor heaps
    ID3D12DescriptorHeap* heaps[] = { g_pESPDescriptorHeap };
    pCommandList->SetDescriptorHeaps(1, heaps);
    
    // Draw ESP boxes
    pCommandList->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_LINELIST);
    pCommandList->IASetVertexBuffers(0, 1, &g_ESPVertexBufferView);
    pCommandList->DrawInstanced(g_ESPVertexCount, 1, 0, 0);
}
```

#### Descriptor Heap Manipulation

```cpp
// Read enemy positions from descriptor heap
void ReadGameData(ID3D12Device* pDevice, ID3D12DescriptorHeap* pHeap) {
    D3D12_DESCRIPTOR_HEAP_DESC heapDesc = pHeap->GetDesc();
    
    // CPU-visible copy
    ID3D12DescriptorHeap* pCPUHeap;
    D3D12_DESCRIPTOR_HEAP_DESC cpuDesc = heapDesc;
    cpuDesc.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_NONE;
    pDevice->CreateDescriptorHeap(&cpuDesc, IID_PPV_ARGS(&pCPUHeap));
    
    // Copy descriptors
    UINT descriptorSize = pDevice->GetDescriptorHandleIncrementSize(heapDesc.Type);
    for (UINT i = 0; i < heapDesc.NumDescriptors; i++) {
        D3D12_CPU_DESCRIPTOR_HANDLE srcHandle = pHeap->GetCPUDescriptorHandleForHeapStart();
        srcHandle.ptr += i * descriptorSize;
        
        D3D12_CPU_DESCRIPTOR_HANDLE dstHandle = pCPUHeap->GetCPUDescriptorHandleForHeapStart();
        dstHandle.ptr += i * descriptorSize;
        
        pDevice->CopyDescriptorsSimple(1, dstHandle, srcHandle, heapDesc.Type);
    }
    
    // Parse constant buffers for player positions
    ParseConstantBuffers(pDevice, pCPUHeap);
}
```

---

### DirectX 13 (DirectX Ultimate) Ray Tracing Hooks

DX13/DXR enables real-time ray tracing, creating new attack surfaces.

#### Hook DispatchRays

```cpp
typedef void (STDMETHODCALLTYPE* DispatchRays_t)(ID3D12GraphicsCommandList4*, const D3D12_DISPATCH_RAYS_DESC*);
DispatchRays_t oDispatchRays = nullptr;

void STDMETHODCALLTYPE hkDispatchRays(ID3D12GraphicsCommandList4* pCommandList, const D3D12_DISPATCH_RAYS_DESC* pDesc) {
    // Modify shader table to inject custom ray generation
    D3D12_DISPATCH_RAYS_DESC modifiedDesc = *pDesc;
    
    // Replace shader records with wallhack-enabled shaders
    modifiedDesc.RayGenerationShaderRecord.StartAddress = g_CustomRayGenShader;
    
    return oDispatchRays(pCommandList, &modifiedDesc);
}
```

#### Shader Table Manipulation

```cpp
// Inject custom hit shader that ignores geometry
void InjectWallhackShader(ID3D12Device5* pDevice) {
    // Compile custom hit shader
    const char* wallhackHitShader = R"(
        [shader("closesthit")]
        void WallhackHit(inout RayPayload payload, in BuiltInTriangleIntersectionAttributes attr) {
            // Always return as if ray passed through (wallhack)
            payload.color = float4(1, 0, 0, 1);  // Red for enemies
            payload.hitT = -1;  // Negative = continue ray
        }
    )";
    
    ID3DBlob* pShaderBlob;
    D3DCompile(wallhackHitShader, strlen(wallhackHitShader), nullptr, nullptr, nullptr, "WallhackHit", "lib_6_3", 0, 0, &pShaderBlob, nullptr);
    
    // Create state object with modified shader
    D3D12_STATE_OBJECT_DESC stateObjectDesc = {};
    // ... configure with custom shader
}
```

---

### Vulkan Layer Injection

Vulkan uses explicit layers for validation and debugging - we can inject our own.

#### Creating a Cheat Layer

```cpp
// VkLayer_CHEAT.cpp
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

static PFN_vkQueuePresentKHR fpNextQueuePresentKHR = nullptr;

VK_LAYER_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkQueuePresentKHR(
    VkQueue queue,
    const VkPresentInfoKHR* pPresentInfo
) {
    // Inject ESP rendering before present
    RenderESP(queue, pPresentInfo);
    
    return fpNextQueuePresentKHR(queue, pPresentInfo);
}

void RenderESP(VkQueue queue, const VkPresentInfoKHR* pPresentInfo) {
    // Get swapchain image
    VkImage image = GetSwapchainImage(pPresentInfo->pSwapchains[0], pPresentInfo->pImageIndices[0]);
    
    // Create command buffer for ESP
    VkCommandBuffer cmdBuffer = CreateESPCommandBuffer();
    
    // Record ESP draw commands
    vkBeginCommandBuffer(cmdBuffer, &beginInfo);
    
    // Transition image layout
    VkImageMemoryBarrier barrier = {};
    barrier.image = image;
    barrier.oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    barrier.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    
    // Begin rendering
    VkRenderingInfo renderInfo = {};
    renderInfo.renderArea = {{0, 0}, {1920, 1080}};
    renderInfo.layerCount = 1;
    
    vkCmdBeginRendering(cmdBuffer, &renderInfo);
    vkCmdBindPipeline(cmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, g_ESPPipeline);
    vkCmdDraw(cmdBuffer, g_ESPVertexCount, 1, 0, 0);
    vkCmdEndRendering(cmdBuffer);
    
    // Transition back
    barrier.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    
    vkEndCommandBuffer(cmdBuffer);
    
    // Submit
    VkSubmitInfo submitInfo = {};
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmdBuffer;
    vkQueueSubmit(queue, 1, &submitInfo, VK_NULL_HANDLE);
}
```

#### Layer Manifest (VkLayer_CHEAT.json)

```json
{
    "file_format_version": "1.2.0",
    "layer": {
        "name": "VK_LAYER_CHEAT",
        "type": "GLOBAL",
        "library_path": ".\\VkLayer_CHEAT.dll",
        "api_version": "1.3.0",
        "implementation_version": "1",
        "description": "ESP and Wallhack Layer",
        "functions": {
            "vkQueuePresentKHR": "vkQueuePresentKHR",
            "vkCreateDevice": "vkCreateDevice"
        }
    }
}
```

#### Installing the Layer

```python
import os
import json
import winreg

def install_vulkan_layer():
    # Layer path
    layer_path = os.path.join(os.getcwd(), "VkLayer_CHEAT.json")
    
    # Add to registry
    key = winreg.CreateKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Khronos\Vulkan\ExplicitLayers")
    winreg.SetValueEx(key, layer_path, 0, winreg.REG_DWORD, 0)
    winreg.CloseKey(key)
    
    print(f"Installed Vulkan layer: {layer_path}")
```

---

### Metal API Hooking (macOS/iOS)

Metal is Apple's low-level graphics API, used by many games on macOS and iOS.

#### Method Swizzling for Metal

```objc
#import <Metal/Metal.h>
#import <objc/runtime.h>

typedef void (*MTLRenderCommandEncoder_DrawPrimitives_t)(id, SEL, MTLPrimitiveType, NSUInteger, NSUInteger);
static MTLRenderCommandEncoder_DrawPrimitives_t original_drawPrimitives = NULL;

void hooked_drawPrimitives(id self, SEL _cmd, MTLPrimitiveType primitiveType, NSUInteger vertexStart, NSUInteger vertexCount) {
    // Detect player models by vertex count
    if (vertexCount > 5000 && vertexCount < 15000) {
        // Change depth state to see through walls
        id<MTLDepthStencilState> wallhackDepthState = GetWallhackDepthState();
        [self setDepthStencilState:wallhackDepthState];
        
        // Change render pipeline to highlight enemies
        id<MTLRenderPipelineState> espPipeline = GetESPPipeline();
        [self setRenderPipelineState:espPipeline];
    }
    
    original_drawPrimitives(self, _cmd, primitiveType, vertexStart, vertexCount);
}

__attribute__((constructor))
static void initialize() {
    Class cls = objc_getClass("MTLRenderCommandEncoder");
    Method originalMethod = class_getInstanceMethod(cls, @selector(drawPrimitives:vertexStart:vertexCount:));
    
    original_drawPrimitives = (MTLRenderCommandEncoder_DrawPrimitives_t)method_getImplementation(originalMethod);
    method_setImplementation(originalMethod, (IMP)hooked_drawPrimitives);
}
```

#### Compute Shader Injection

```objc
// Inject compute shader to read game memory via GPU
- (void)injectMemoryReadShader:(id<MTLDevice>)device {
    // Metal shading language kernel
    NSString* kernelSource = @R"(
        #include <metal_stdlib>
        using namespace metal;
        
        kernel void readGameMemory(
            device float4* positions [[buffer(0)]],
            device float4* output [[buffer(1)]],
            uint id [[thread_position_in_grid]]
        ) {
            // Read player positions from GPU buffer
            output[id] = positions[id];
        }
    )";
    
    NSError* error = nil;
    id<MTLLibrary> library = [device newLibraryWithSource:kernelSource options:nil error:&error];
    id<MTLFunction> function = [library newFunctionWithName:@"readGameMemory"];
    id<MTLComputePipelineState> pipeline = [device newComputePipelineStateWithFunction:function error:&error];
    
    // Execute kernel
    id<MTLCommandBuffer> commandBuffer = [commandQueue commandBuffer];
    id<MTLComputeCommandEncoder> encoder = [commandBuffer computeCommandEncoder];
    
    [encoder setComputePipelineState:pipeline];
    [encoder setBuffer:gamePositionBuffer offset:0 atIndex:0];
    [encoder setBuffer:outputBuffer offset:0 atIndex:1];
    [encoder dispatchThreadgroups:MTLSizeMake(1024, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
    [encoder endEncoding];
    [commandBuffer commit];
}
```

---

### GPU Compute-Based ESP (Invisible to CPU Scanners)

Run ESP detection entirely on GPU to avoid CPU memory scanning by anti-cheat.

#### CUDA-Based Player Detection

```cpp
#include <cuda_runtime.h>
#include <device_launch_parameters.h>

__global__ void detectPlayers(float4* positions, int playerCount, float3 cameraPos, bool* visible, float* distances) {
    int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= playerCount) return;
    
    float4 pos = positions[idx];
    
    // Calculate distance
    float dx = pos.x - cameraPos.x;
    float dy = pos.y - cameraPos.y;
    float dz = pos.z - cameraPos.z;
    float dist = sqrtf(dx*dx + dy*dy + dz*dz);
    
    distances[idx] = dist;
    
    // Check visibility (simplified - real implementation would raytrace)
    visible[idx] = (dist < 100.0f);
}

void RunESPOnGPU(ID3D12Resource* positionBuffer, int playerCount) {
    // Map D3D12 buffer to CUDA
    cudaExternalMemory_t extMem;
    cudaExternalMemoryHandleDesc memDesc = {};
    memDesc.type = cudaExternalMemoryHandleTypeD3D12Resource;
    memDesc.handle.win32.handle = GetSharedHandle(positionBuffer);
    cudaImportExternalMemory(&extMem, &memDesc);
    
    // Get device pointer
    float4* d_positions;
    cudaExternalMemoryGetMappedBuffer((void**)&d_positions, extMem, NULL);
    
    // Allocate output
    bool* d_visible;
    float* d_distances;
    cudaMalloc(&d_visible, playerCount * sizeof(bool));
    cudaMalloc(&d_distances, playerCount * sizeof(float));
    
    // Launch kernel
    int threadsPerBlock = 256;
    int blocks = (playerCount + threadsPerBlock - 1) / threadsPerBlock;
    
    float3 cameraPos = GetCameraPosition();
    detectPlayers<<<blocks, threadsPerBlock>>>(d_positions, playerCount, cameraPos, d_visible, d_distances);
    
    // Copy results
    bool* h_visible = new bool[playerCount];
    cudaMemcpy(h_visible, d_visible, playerCount * sizeof(bool), cudaMemcpyDeviceToHost);
    
    // Render ESP based on GPU results
    RenderESPFromGPUData(h_visible, playerCount);
}
```

#### OpenCL Compute ESP

```cpp
const char* espKernel = R"(
    __kernel void processESP(
        __global float4* playerPositions,
        __global float4* playerBounds,
        __constant float16* viewProjection,
        __global float2* screenPositions,
        int playerCount
    ) {
        int gid = get_global_id(0);
        if (gid >= playerCount) return;
        
        // Transform world position to screen
        float4 pos = playerPositions[gid];
        float4 projected = viewProjection * pos;
        
        if (projected.w > 0) {
            screenPositions[gid].x = (projected.x / projected.w) * 0.5f + 0.5f;
            screenPositions[gid].y = (projected.y / projected.w) * 0.5f + 0.5f;
        }
    }
)";

void SetupOpenCLESP() {
    cl_platform_id platform;
    clGetPlatformIDs(1, &platform, NULL);
    
    cl_device_id device;
    clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 1, &device, NULL);
    
    cl_context context = clCreateContext(NULL, 1, &device, NULL, NULL, NULL);
    cl_command_queue queue = clCreateCommandQueue(context, device, 0, NULL);
    
    // Compile kernel
    cl_program program = clCreateProgramWithSource(context, 1, &espKernel, NULL, NULL);
    clBuildProgram(program, 1, &device, NULL, NULL, NULL);
    cl_kernel kernel = clCreateKernel(program, "processESP", NULL);
    
    // Create buffers
    cl_mem posBuffer = clCreateBuffer(context, CL_MEM_READ_ONLY, sizeof(float4) * 64, NULL, NULL);
    cl_mem screenBuffer = clCreateBuffer(context, CL_MEM_WRITE_ONLY, sizeof(float2) * 64, NULL, NULL);
    
    // Set arguments and execute
    clSetKernelArg(kernel, 0, sizeof(cl_mem), &posBuffer);
    clSetKernelArg(kernel, 3, sizeof(cl_mem), &screenBuffer);
    
    size_t globalSize = 64;
    clEnqueueNDRangeKernel(queue, kernel, 1, NULL, &globalSize, NULL, 0, NULL, NULL);
}
```

---

### Graphics API Comparison

| API        | Hook Point           | Difficulty | Detection Risk | Performance |
|------------|---------------------|------------|----------------|-------------|
| DX9        | EndScene/Present    | Easy       | High           | Good        |
| DX11       | Present             | Easy       | High           | Good        |
| DX12       | ExecuteCommandLists | Hard       | Medium         | Excellent   |
| DX13/DXR   | DispatchRays        | Very Hard  | Low            | Excellent   |
| Vulkan     | vkQueuePresentKHR   | Medium     | Medium         | Excellent   |
| OpenGL     | wglSwapBuffers      | Easy       | High           | Good        |
| Metal      | presentDrawable     | Medium     | Low            | Excellent   |
| GPU Compute| N/A (Invisible)     | Very Hard  | Very Low       | Excellent   |

---

