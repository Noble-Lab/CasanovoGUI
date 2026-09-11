# Performance

CPU/GPU speed and memory use, measured in CasanovoGUI-managed environments.
Casanovo runs on either CPU or GPU.

Repeat the test for each major Casanovo or CasanovoGUI release with
[speed_benchmark.py](../.github/scripts/speed_benchmark.py), using the same input
and workstation.

## Test data

One complete data-dependent acquisition run of a HeLa tryptic digest
(`Loo_20240205_BoAI_46_DDA_HeLa01`, ProteomeXchange
[PXD066485](https://proteomecentral.proteomexchange.org/cgi/GetDataset?ID=PXD066485)),
converted to mzML. The mzML header identifies the instrument as an Orbitrap Eclipse.
Both CPU and GPU tests use the whole file.

<!-- BENCHMARK:BEGIN -->
## Casanovo benchmark

- **Benchmarked:** 2026-09-01 to 2026-09-02 (America/Los_Angeles)
- **Input:** Loo_20240205_BoAI_46_DDA_HeLa01.mzML
- **Operating system:** Windows 11 (build 26200)
- **CPU:** Intel(R) Core(TM) Ultra 7 265K (20 cores / 20 threads)
- **System memory:** 127 GiB
- **GPU:** NVIDIA RTX 5000 Ada Generation (driver 595.95)
- **Casanovo:** 5.2.1
- **PyTorch:** 2.5.1+cu121 (CUDA 12.1)
- **Model:** casanovo_orbitrap_v5-2-0.ckpt
- **Settings:** batch size: 1024; beams: 1; precision: 32-true

| Device | MS/MS spectra | Total time | Spectra/s | Peak private RAM |
|---|---:|---:|---:|---:|
| GPU | 86,311 | 530 s | 163 | 1.71 GiB |
| CPU | 86,311 | 10,926 s | 8 | 5.69 GiB |

Total time includes model loading and result writing. These are single runs per device.

Private RAM excludes shared pages; the operating system and other applications need extra RAM.
The sampler uses resident memory (RSS) where private memory (USS) is unavailable.
RAM is sampled about every two seconds; brief peaks may be missed.

<!-- BENCHMARK:END -->

<!-- GPU-MEMORY:BEGIN -->
## GPU memory budget test

- **Benchmarked:** 2026-09-10 to 2026-09-11 (America/Los_Angeles)
- **Input:** Loo_20240205_BoAI_46_DDA_HeLa01.mzML
- **GPU:** NVIDIA RTX 5000 Ada Generation (30 GiB visible; driver 595.95)
- **Operating system:** Windows 11 (build 26200)
- **Casanovo:** 5.2.1
- **CasanovoGUI:** 1.4.0 (source checkout)
- **PyTorch:** 2.14.0+cu132 (CUDA 13.2)
- **Model:** casanovo_orbitrap_v5-2-0.ckpt
- **Settings:** batch size: 1024; beams: 1; precision: 32-true

Each run uses the same input, model and settings in a new process.
The PyTorch limit covers tensors and cached memory. Peak GPU memory also includes CUDA overhead.

| PyTorch limit (GiB) | Outcome | Total time (s) | Peak GPU memory (GiB) | PSM rows | Matches baseline |
|---:|---|---:|---:|---:|---|
| Unrestricted | Completed | 513.2 | 19.64 | 86,311 | Yes |
| 4.0 | Completed | 498.8 | 4.23 | 86,311 | Yes |
| 2.5 | CUDA OOM | 107.8 | 2.73 | 9,216 | n/a |
| 3.0 | CUDA OOM | 191.5 | 3.30 | 27,646 | n/a |
| 3.5 | Completed | 507.9 | 3.82 | 86,311 | Yes |
| 3.5 | Completed | 500.8 | 3.79 | 86,311 | Yes |

**Lowest tested successful PyTorch limit: 3.5 GiB (2 complete runs).**
**Observed GPU memory at that limit reached 3.82 GiB.**
A budget test does not verify that a physical card of that size can run the job.

"Matches baseline" compares every PSM row with the unrestricted run.
OOM means out of memory; those row counts are partial results.

GPU memory is sampled about every two seconds; brief peaks may be missed.
Device readings subtract starting usage (0 MiB in these runs).
Other GPU activity can affect these readings.

<!-- GPU-MEMORY:END -->

## Running the benchmark for a release

Use the release's GUI-managed Casanovo environment, the full test input and a local
model checkpoint. Run from the repository root on a machine with one NVIDIA GPU;
update the checkpoint and output-directory name for the release.

```powershell
& "$env:USERPROFILE\.casanovo-gui\.venv\Scripts\python.exe" .github\scripts\speed_benchmark.py `
    --input test_data\Loo_20240205_BoAI_46_DDA_HeLa01\Loo_20240205_BoAI_46_DDA_HeLa01.mzML `
    --casanovo "$env:USERPROFILE\.casanovo-gui\.venv\Scripts\casanovo.exe" `
    --model "$env:LOCALAPPDATA\casanovo\casanovo_orbitrap_v5-2-0.ckpt" `
    --devices gpu cpu `
    --gpu-memory-budgets 4 2.5 3 3.5 3.5 `
    --out-dir test_data\benchmark-casanovo-5.2.1-gui-1.4.0 `
    --update-doc docs\performance.md
```

Use a new or empty output directory and allow several hours. The script uses the
release's default settings. Adjust the PyTorch limits as needed and repeat the
lowest successful limit. To reuse this report's settings, add
`--config docs\benchmarks\gpu-memory-2026-09-11.yaml`.

Results and logs are saved under `--out-dir`, including `benchmark.md` and
`benchmark.json`. `--update-doc` updates both result tables after a successful run;
omit it to save results without changing this page.
