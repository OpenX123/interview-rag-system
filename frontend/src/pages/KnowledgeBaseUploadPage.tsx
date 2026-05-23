import {ChangeEvent, DragEvent, useCallback, useEffect, useMemo, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import {
  AlertCircle,
  ChevronDown,
  Copy,
  Eye,
  FileText,
  Hash,
  Loader2,
  Search,
  Sliders,
  Upload,
  UploadCloud,
  X,
} from 'lucide-react';
import {
  knowledgeBaseApi,
  KnowledgeBaseChunk,
  PreviewChunksResponse,
  UploadKnowledgeBaseResponse,
} from '../api/knowledgebase';
import {llmProviderApi} from '../api/llmProvider';
import type {ProviderItem} from '../types/llmProvider';

interface Props {
  onUploadComplete: (result: UploadKnowledgeBaseResponse) => void;
  onBack: () => void;
}

const DEFAULT_CHUNK_SIZE = 800;
const MIN_CHUNK_SIZE = 100;
const MAX_CHUNK_SIZE = 2000;

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

export default function KnowledgeBaseUploadPage({onUploadComplete, onBack}: Props) {
  // ===== 表单状态 =====
  const [file, setFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [name, setName] = useState('');
  const [chunkSize, setChunkSize] = useState<number>(DEFAULT_CHUNK_SIZE);
  const [embeddingProvider, setEmbeddingProvider] = useState<string>(''); // 空字符串 = 使用全局默认
  const [advancedOpen, setAdvancedOpen] = useState(true);

  // ===== Provider 列表（只显示 supportsEmbedding 的） =====
  const [providers, setProviders] = useState<ProviderItem[]>([]);
  const [providersLoading, setProvidersLoading] = useState(false);

  useEffect(() => {
    setProvidersLoading(true);
    llmProviderApi.list()
      .then((all) => setProviders(all.filter((p) => p.supportsEmbedding)))
      .catch(() => setProviders([]))
      .finally(() => setProvidersLoading(false));
  }, []);

  // ===== 预览状态 =====
  const [preview, setPreview] = useState<PreviewChunksResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewKeyword, setPreviewKeyword] = useState('');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  // 切换文件或 chunkSize 时清空预览（避免误以为旧预览还有效）
  useEffect(() => {
    setPreview(null);
    setPreviewError(null);
    setPreviewKeyword('');
  }, [file, chunkSize]);

  // ===== 上传状态 =====
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  // ===== 文件选择 =====
  const handleDragOver = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  }, []);
  const handleDragLeave = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
  }, []);
  const handleDrop = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const f = e.dataTransfer.files?.[0];
    if (f) setFile(f);
  }, []);
  const handleFileChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) setFile(f);
  }, []);

  // ===== 预览 =====
  const handlePreview = async () => {
    if (!file) return;
    setPreviewLoading(true);
    setPreviewError(null);
    try {
      const result = await knowledgeBaseApi.previewChunks(file, chunkSize);
      setPreview(result);
    } catch (e) {
      const msg = e instanceof Error ? e.message : '预览失败';
      setPreviewError(msg);
    } finally {
      setPreviewLoading(false);
    }
  };

  // ===== 上传 =====
  const handleUpload = async () => {
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    try {
      const result = await knowledgeBaseApi.uploadKnowledgeBase(
        file,
        name.trim() || undefined,
        undefined,
        {
          chunkSize,
          embeddingProvider: embeddingProvider || undefined,
        },
      );
      onUploadComplete(result);
    } catch (e) {
      const msg = e instanceof Error ? e.message : '上传失败';
      setUploadError(msg);
      setUploading(false);
    }
  };

  const handleCopy = async (chunk: KnowledgeBaseChunk) => {
    try {
      await navigator.clipboard.writeText(chunk.content);
      setCopiedId(chunk.id);
      setTimeout(() => setCopiedId(null), 1500);
    } catch {/* ignore */}
  };

  const filteredChunks = useMemo(() => {
    if (!preview) return [];
    if (!previewKeyword.trim()) return preview.chunks;
    const kw = previewKeyword.trim().toLowerCase();
    return preview.chunks.filter((c) => c.content.toLowerCase().includes(kw));
  }, [preview, previewKeyword]);

  const previewStats = useMemo(() => {
    if (!preview || preview.chunks.length === 0) return null;
    const total = preview.chunks.reduce((s, c) => s + c.contentLength, 0);
    return {
      avg: Math.round(total / preview.chunks.length),
      sum: total,
    };
  }, [preview]);

  const canPreview = file && !previewLoading && !uploading;
  const canUpload = file && !uploading;

  return (
    <div className="max-w-4xl mx-auto py-8 px-4">
      {/* 标题 */}
      <motion.div
        className="mb-8"
        initial={{opacity: 0, y: 10}}
        animate={{opacity: 1, y: 0}}
      >
        <h1 className="font-display text-3xl font-semibold text-slate-900 tracking-tight">
          上传知识库
        </h1>
        <p className="mt-2 text-slate-600">
          配置分块策略与 Embedding 模型，建议先预览分块效果再正式入库
        </p>
      </motion.div>

      {/* 文件选择区 */}
      <motion.div
        className={`relative bg-white border-2 border-dashed rounded-xl px-8 py-10 cursor-pointer transition-colors ${
          dragOver ? 'border-primary-500 bg-primary-50/40' : 'border-slate-200 hover:border-slate-300'
        }`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => !file && document.getElementById('kb-upload-input')?.click()}
        initial={{opacity: 0, y: 10}}
        animate={{opacity: 1, y: 0}}
        transition={{delay: 0.05}}
      >
        <input
          type="file"
          id="kb-upload-input"
          className="hidden"
          accept=".pdf,.doc,.docx,.txt,.md"
          onChange={handleFileChange}
          disabled={uploading}
        />
        <AnimatePresence mode="wait">
          {file ? (
            <motion.div
              key="selected"
              initial={{opacity: 0}}
              animate={{opacity: 1}}
              exit={{opacity: 0}}
              className="flex items-center gap-4"
            >
              <div className="w-12 h-12 bg-primary-100 text-primary-600 rounded-lg flex items-center justify-center shrink-0">
                <FileText className="w-6 h-6" strokeWidth={1.75} />
              </div>
              <div className="flex-1 min-w-0">
                <p className="font-medium text-slate-900 truncate">{file.name}</p>
                <p className="text-sm text-slate-500 mt-0.5">{formatFileSize(file.size)}</p>
              </div>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  setFile(null);
                }}
                className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 rounded-md transition-colors"
                title="移除文件"
              >
                <X className="w-5 h-5" />
              </button>
            </motion.div>
          ) : (
            <motion.div
              key="empty"
              initial={{opacity: 0}}
              animate={{opacity: 1}}
              exit={{opacity: 0}}
              className="flex flex-col items-center text-center"
            >
              <div className={`w-14 h-14 rounded-xl flex items-center justify-center transition-colors ${
                dragOver ? 'bg-primary-100 text-primary-600' : 'bg-slate-100 text-slate-400'
              }`}>
                <Upload className="w-7 h-7" strokeWidth={1.75} />
              </div>
              <h3 className="mt-3 text-base font-medium text-slate-900">点击或拖拽文件至此处</h3>
              <p className="mt-1 text-sm text-slate-500">支持 PDF · DOCX · DOC · TXT · MD，单文件最大 50MB</p>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>

      {/* 知识库名称 */}
      {file && (
        <motion.div
          className="mt-6 bg-white border border-slate-200 rounded-xl p-5"
          initial={{opacity: 0, y: 10}}
          animate={{opacity: 1, y: 0}}
        >
          <label className="block text-sm font-medium text-slate-800 mb-2">
            知识库名称 <span className="text-slate-400 font-normal">（可选）</span>
          </label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="留空则使用文件名"
            className="w-full px-3 py-2 text-sm bg-white border border-slate-200 rounded-md text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-primary-500/15 focus:border-primary-500 transition-colors"
            disabled={uploading}
          />
        </motion.div>
      )}

      {/* 高级设置：分块 + Embedding */}
      {file && (
        <motion.div
          className="mt-4 bg-white border border-slate-200 rounded-xl overflow-hidden"
          initial={{opacity: 0, y: 10}}
          animate={{opacity: 1, y: 0}}
        >
          <button
            onClick={() => setAdvancedOpen(!advancedOpen)}
            className="w-full px-5 py-4 flex items-center justify-between hover:bg-slate-50 transition-colors"
          >
            <div className="flex items-center gap-2.5">
              <Sliders className="w-4 h-4 text-slate-500" />
              <span className="text-sm font-medium text-slate-900">高级设置</span>
              <span className="text-xs text-slate-400">分块大小 · Embedding 模型</span>
            </div>
            <ChevronDown className={`w-4 h-4 text-slate-400 transition-transform ${advancedOpen ? 'rotate-180' : ''}`} />
          </button>
          <AnimatePresence initial={false}>
            {advancedOpen && (
              <motion.div
                initial={{height: 0, opacity: 0}}
                animate={{height: 'auto', opacity: 1}}
                exit={{height: 0, opacity: 0}}
                transition={{duration: 0.2}}
                className="overflow-hidden"
              >
                <div className="px-5 pb-5 pt-1 grid grid-cols-1 md:grid-cols-2 gap-5 border-t border-slate-100">
                  {/* 分块大小 */}
                  <div>
                    <label className="flex items-baseline justify-between text-sm font-medium text-slate-800 mb-2">
                      <span>分块大小</span>
                      <span className="text-xs text-slate-500">token 数，{MIN_CHUNK_SIZE}–{MAX_CHUNK_SIZE}</span>
                    </label>
                    <div className="flex items-center gap-3">
                      <input
                        type="range"
                        min={MIN_CHUNK_SIZE}
                        max={MAX_CHUNK_SIZE}
                        step={50}
                        value={chunkSize}
                        onChange={(e) => setChunkSize(Number(e.target.value))}
                        className="flex-1 accent-primary-500"
                        disabled={uploading}
                      />
                      <input
                        type="number"
                        min={MIN_CHUNK_SIZE}
                        max={MAX_CHUNK_SIZE}
                        value={chunkSize}
                        onChange={(e) => {
                          const v = Number(e.target.value);
                          if (Number.isFinite(v)) {
                            setChunkSize(Math.max(MIN_CHUNK_SIZE, Math.min(MAX_CHUNK_SIZE, v)));
                          }
                        }}
                        className="w-20 px-2 py-1.5 text-sm text-center bg-white border border-slate-200 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500/15 focus:border-primary-500"
                        disabled={uploading}
                      />
                    </div>
                    <p className="mt-1.5 text-xs text-slate-500">
                      约 {Math.round(chunkSize * 3.5)} 个中文字符/块；越大上下文越完整，越小召回越精准
                    </p>
                  </div>

                  {/* Embedding Provider */}
                  <div>
                    <label className="flex items-baseline justify-between text-sm font-medium text-slate-800 mb-2">
                      <span>Embedding 模型</span>
                      <span className="text-xs text-slate-500">索引方式</span>
                    </label>
                    <select
                      value={embeddingProvider}
                      onChange={(e) => setEmbeddingProvider(e.target.value)}
                      className="w-full px-3 py-2 text-sm bg-white border border-slate-200 rounded-md text-slate-900 focus:outline-none focus:ring-2 focus:ring-primary-500/15 focus:border-primary-500 transition-colors"
                      disabled={uploading || providersLoading}
                    >
                      <option value="">
                        {providersLoading ? '加载中…' : '使用全局默认'}
                      </option>
                      {providers.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.id} · {p.embeddingModel || p.model}
                          {p.embeddingDimensions ? ` (${p.embeddingDimensions}d)` : ''}
                        </option>
                      ))}
                    </select>
                    {providers.length === 0 && !providersLoading && (
                      <p className="mt-1.5 text-xs text-slate-500">未发现支持 embedding 的 provider</p>
                    )}
                  </div>
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </motion.div>
      )}

      {/* 错误提示 */}
      <AnimatePresence>
        {uploadError && (
          <motion.div
            initial={{opacity: 0, y: -8}}
            animate={{opacity: 1, y: 0}}
            exit={{opacity: 0, y: -8}}
            className="mt-4 p-3 bg-red-50 border border-red-200 rounded-md text-red-700 text-sm flex items-start gap-2"
          >
            <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
            <span>{uploadError}</span>
          </motion.div>
        )}
      </AnimatePresence>

      {/* 操作按钮 */}
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <button
          onClick={onBack}
          className="px-4 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-md hover:bg-slate-100 hover:border-slate-300 transition-colors"
          disabled={uploading}
        >
          返回
        </button>
        <div className="flex-1" />
        <button
          onClick={handlePreview}
          disabled={!canPreview}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-md hover:bg-slate-100 hover:border-slate-300 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {previewLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Eye className="w-4 h-4" />}
          预览分块
        </button>
        <button
          onClick={handleUpload}
          disabled={!canUpload}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary-500 rounded-md hover:bg-primary-600 active:bg-primary-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {uploading ? <Loader2 className="w-4 h-4 animate-spin" /> : <UploadCloud className="w-4 h-4" />}
          {uploading ? '上传中…' : '开始上传'}
        </button>
      </div>

      {/* 预览区域 */}
      <AnimatePresence>
        {(preview || previewError) && (
          <motion.section
            initial={{opacity: 0, y: 10}}
            animate={{opacity: 1, y: 0}}
            exit={{opacity: 0, y: 10}}
            className="mt-8 bg-white border border-slate-200 rounded-xl overflow-hidden"
          >
            <header className="px-5 pt-5 pb-3 border-b border-slate-100">
              <div className="flex items-center justify-between gap-4">
                <div>
                  <div className="text-[11px] uppercase tracking-[0.08em] text-slate-500 font-medium mb-1">
                    分块预览（chunkSize = {chunkSize}）
                  </div>
                  <h3 className="font-display text-lg font-semibold text-slate-900">切分结果</h3>
                </div>
                <button
                  onClick={() => {
                    setPreview(null);
                    setPreviewError(null);
                  }}
                  className="p-1.5 text-slate-400 hover:text-slate-700 hover:bg-slate-100 rounded transition-colors"
                  title="关闭预览"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              {preview && previewStats && (
                <div className="mt-3 flex items-center gap-4 text-[13px] text-slate-600">
                  <div className="flex items-center gap-1.5">
                    <Hash className="w-3.5 h-3.5 text-primary-500" />
                    <span className="font-medium text-slate-900">{preview.totalChunks}</span>
                    <span>个分块</span>
                    {preview.truncated && (
                      <span className="ml-1 px-1.5 py-0.5 text-[10px] bg-amber-100 text-amber-700 rounded">
                        仅展示前 {preview.returnedChunks}
                      </span>
                    )}
                  </div>
                  <span className="text-slate-300">·</span>
                  <span><span className="font-medium text-slate-900">{previewStats.sum.toLocaleString()}</span> 总字符</span>
                  <span className="text-slate-300">·</span>
                  <span>平均 <span className="font-medium text-slate-900">{previewStats.avg}</span> 字/块</span>
                </div>
              )}

              {preview && preview.chunks.length > 0 && (
                <div className="mt-3 relative">
                  <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                  <input
                    type="text"
                    value={previewKeyword}
                    onChange={(e) => setPreviewKeyword(e.target.value)}
                    placeholder="按文本搜索分块…"
                    className="w-full pl-9 pr-3 py-2 text-sm bg-white border border-slate-200 rounded-md text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-primary-500/15 focus:border-primary-500"
                  />
                  {previewKeyword && (
                    <span className="absolute right-3 top-1/2 -translate-y-1/2 text-[11px] text-slate-500">
                      {filteredChunks.length}/{preview.chunks.length}
                    </span>
                  )}
                </div>
              )}
            </header>

            <div className="px-5 py-4 max-h-[600px] overflow-y-auto scrollbar-thin space-y-3">
              {previewError && (
                <div className="flex items-start gap-3 p-3 bg-red-50 border border-red-200 rounded-md text-red-700 text-sm">
                  <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
                  <span>{previewError}</span>
                </div>
              )}

              {preview && preview.chunks.length === 0 && (
                <div className="text-center py-10 text-slate-500 text-sm">该文件未切分出任何分块</div>
              )}

              {preview && preview.chunks.length > 0 && filteredChunks.length === 0 && (
                <div className="text-center py-10 text-slate-500 text-sm">
                  没有匹配「{previewKeyword}」的分块
                </div>
              )}

              {filteredChunks.map((chunk) => (
                <article
                  key={chunk.id}
                  className="group bg-white border border-slate-200 rounded-lg overflow-hidden hover:border-slate-300 transition-colors"
                >
                  <header className="flex items-center justify-between px-4 py-2 bg-slate-50 border-b border-slate-100">
                    <div className="flex items-center gap-3 text-[12px]">
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-primary-50 text-primary-700 rounded-md font-medium">
                        # {chunk.chunkIndex + 1}
                        {chunk.totalChunks > 0 && (
                          <span className="text-primary-500/70 font-normal">/{chunk.totalChunks}</span>
                        )}
                      </span>
                      <span className="text-slate-500">{chunk.contentLength} 字</span>
                    </div>
                    <button
                      onClick={() => handleCopy(chunk)}
                      className="opacity-0 group-hover:opacity-100 flex items-center gap-1 px-2 py-1 text-[11px] text-slate-500 hover:text-slate-900 hover:bg-white rounded transition-all"
                      title="复制此分块"
                    >
                      <Copy className="w-3 h-3" />
                      {copiedId === chunk.id ? '已复制' : '复制'}
                    </button>
                  </header>
                  <pre className="px-4 py-3 text-[13px] leading-relaxed text-slate-800 whitespace-pre-wrap break-words font-sans">
                    {chunk.content || <span className="text-slate-400 italic">（空内容）</span>}
                  </pre>
                </article>
              ))}
            </div>
          </motion.section>
        )}
      </AnimatePresence>
    </div>
  );
}
