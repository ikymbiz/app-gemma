# models/

User-supplied GGUF model files go here. Files in this directory are intentionally
not tracked by version control — see `.gitignore`.

## Recommended files

| Filename                         | Size    | RAM needed | Hugging Face source                                                  |
|----------------------------------|---------|-----------:|----------------------------------------------------------------------|
| `gemma-4-E2B-it-Q4_K_M.gguf`     | ~2.0 GB |     6 GB+  | `unsloth/gemma-4-E2B-it-GGUF` (or `lmstudio-community/...`)          |
| `gemma-4-E4B-it-Q4_K_M.gguf`     | ~4.0 GB |     8 GB+  | `unsloth/gemma-4-E4B-it-GGUF`                                        |
| `gemma-4-E4B-it-Q5_K_M.gguf`     | ~5.0 GB |    12 GB+  | `unsloth/gemma-4-E4B-it-GGUF`                                        |

E2B is the safest first choice. E4B is more capable but tighter on memory.

## How to download

### PowerShell (Windows)

```powershell
curl.exe -L -o .\models\gemma-4-E2B-it-Q4_K_M.gguf `
  https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf
```

### bash (macOS / Linux)

```bash
curl -L -o ./models/gemma-4-E2B-it-Q4_K_M.gguf \
  https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf
```

### huggingface-cli

```bash
pip install -U "huggingface_hub[cli]"
hf download unsloth/gemma-4-E2B-it-GGUF gemma-4-E2B-it-Q4_K_M.gguf --local-dir ./models
```

## File format note

Only **GGUF** files (`.gguf` extension) work with llama-server. The original `safetensors`
files from `google/...` repositories are not usable here — they are the raw weights
intended for training frameworks.
