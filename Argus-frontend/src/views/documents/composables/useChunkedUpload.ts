import { ref, reactive } from 'vue'
import {
  initDocumentUpload,
  uploadDocumentChunk,
  completeDocumentUpload,
} from '@/api/document'
import { extractApiError } from '@/api/http'

/**
 * 每个分片的大小：5MB。
 *
 * 下限需满足 MinIO 服务端合并（compose）对「非最后分片 >= 5MB」的要求；
 * 上限受后端约束（DocumentUploadService#MAX_CHUNK_SIZE = 10MB）。
 */
const CHUNK_SIZE = 5 * 1024 * 1024

/** 分片上传的阶段，驱动上传弹窗的分阶段提示文案 */
export type ChunkedUploadStage = 'hashing' | 'init' | 'uploading' | 'completing' | null

/**
 * 大文件分片上传组合式函数。
 *
 * 完整链路：计算文件 SHA-256 指纹（hashing）→ 初始化会话（init）
 * → 逐个上传分片（uploading，支持断点续传）→ 合并完成（completing）。
 *
 * 调用方（UploadDialog）通过 `reactive(useChunkedUpload())` 包裹后，
 * 直接读取 `isUploading` / `progress` / `error` / `stage` / `chunkProgress` 等响应式状态。
 */
export function useChunkedUpload() {
  /** 是否正在上传（含计算指纹、合并阶段） */
  const isUploading = ref(false)
  /** 整体进度（0-100） */
  const progress = ref(0)
  /** 上传失败时的错误信息，取消上传时保持为空 */
  const error = ref('')
  /** 当前阶段，null 表示未在分片上传流程中 */
  const stage = ref<ChunkedUploadStage>(null)
  /** 计算文件指纹阶段的进度文案（百分比字符串） */
  const hashingProgress = ref('')
  /** 分片进度：已上传分片数 / 总分片数 */
  const chunkProgress = reactive({ uploaded: 0, total: 0 })

  /** 取消上传用的 AbortController，用于中断进行中的请求 */
  let abortController: AbortController | null = null
  /** 是否已被用户主动取消（区别于网络/业务错误） */
  let cancelled = false

  /**
   * 分片上传整个文件。
   *
   * @param file    待上传的文件对象
   * @param groupId 目标群组 ID
   * @throws 上传失败或取消时抛出异常，由调用方捕获并决定如何展示
   */
  async function uploadFile(file: File, groupId: number): Promise<void> {
    if (isUploading.value) return
    reset()
    isUploading.value = true
    abortController = new AbortController()

    try {
      // 1. 计算文件指纹（SHA-256），供后端做秒传 / 续传判断
      stage.value = 'hashing'
      const fileHash = await computeFileHash(file, (pct) => {
        hashingProgress.value = `${pct}%`
      })
      throwIfCancelled()

      // 2. 初始化分片上传会话
      stage.value = 'init'
      const chunkCount = Math.ceil(file.size / CHUNK_SIZE)
      const initResult = await initDocumentUpload(
        {
          groupId,
          fileName: file.name,
          fileSize: file.size,
          contentType: file.type || 'application/octet-stream',
          fileHash,
          chunkSize: CHUNK_SIZE,
          chunkCount,
        },
        abortController.signal,
      )

      // 秒传：文件哈希命中，直接复用已有文件，无需再传分片
      if (initResult.instantUpload) {
        progress.value = 100
        return
      }

      const uploadId = initResult.uploadId
      if (!uploadId) {
        throw new Error('初始化上传失败：未返回 uploadId')
      }

      const uploaded = new Set<number>(initResult.uploadedChunks ?? [])
      chunkProgress.total = chunkCount
      chunkProgress.uploaded = uploaded.size
      progress.value = Math.round((uploaded.size / chunkCount) * 100)

      // 3. 逐个上传分片（跳过已上传的分片，支持断点续传）
      stage.value = 'uploading'
      for (let i = 0; i < chunkCount; i++) {
        throwIfCancelled()
        if (uploaded.has(i)) continue

        const start = i * CHUNK_SIZE
        const end = Math.min(start + CHUNK_SIZE, file.size)
        const chunkBlob = file.slice(start, end)
        const chunkHash = await computeBlobHash(chunkBlob)

        await uploadDocumentChunk(
          {
            uploadId,
            chunkIndex: i,
            chunkHash,
            chunk: chunkBlob,
          },
          undefined,
          abortController.signal,
        )

        uploaded.add(i)
        chunkProgress.uploaded = uploaded.size
        progress.value = Math.round((uploaded.size / chunkCount) * 100)
      }

      // 4. 合并分片，触发后端异步 ETL
      stage.value = 'completing'
      await completeDocumentUpload(uploadId, abortController.signal)
      progress.value = 100
    } catch (err) {
      // 主动取消不视为错误，保持 error 为空，由调用方识别 AbortError
      error.value = cancelled ? '' : extractApiError(err, '上传文件失败')
      throw err
    } finally {
      isUploading.value = false
      stage.value = null
      hashingProgress.value = ''
      abortController = null
      cancelled = false
    }
  }

  /** 取消当前上传（中断请求，触发 uploadFile 抛出 AbortError） */
  function cancel() {
    cancelled = true
    abortController?.abort()
  }

  /** 重置所有状态到初始值 */
  function reset() {
    isUploading.value = false
    progress.value = 0
    error.value = ''
    stage.value = null
    hashingProgress.value = ''
    chunkProgress.uploaded = 0
    chunkProgress.total = 0
    cancelled = false
  }

  function throwIfCancelled() {
    if (cancelled) {
      throw new DOMException('已取消', 'AbortError')
    }
  }

  return {
    isUploading,
    progress,
    error,
    stage,
    hashingProgress,
    chunkProgress,
    uploadFile,
    cancel,
    reset,
  }
}

/** 计算整个文件的 SHA-256 哈希（十六进制小写），并通过 onProgress 报告进度 */
async function computeFileHash(file: File, onProgress?: (pct: number) => void): Promise<string> {
  onProgress?.(0)
  const buf = await file.arrayBuffer()
  onProgress?.(60)
  const hash = await sha256Hex(buf)
  onProgress?.(100)
  return hash
}

/** 计算单个分片的 SHA-256 哈希（十六进制小写） */
async function computeBlobHash(blob: Blob): Promise<string> {
  return sha256Hex(await blob.arrayBuffer())
}

/** 使用 Web Crypto API 计算 SHA-256，并转为十六进制小写字符串 */
async function sha256Hex(buf: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', buf)
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}
