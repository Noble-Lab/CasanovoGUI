# Performance

How fast Casanovo runs, and how much memory it needs, measured with the same
Casanovo installation that CasanovoGUI sets up. **A GPU is not required**: it
makes a run faster, not possible.

The figures below are re-measured for each major Casanovo release on the same
workstation, so that the performance of the current release is always on record
and can be compared with earlier ones. The script that produces them is
[`.github/scripts/speed_benchmark.py`](../.github/scripts/speed_benchmark.py).

## Test data

One complete data-dependent acquisition run of a HeLa tryptic digest acquired on
an Orbitrap Eclipse (`Loo_20240205_BoAI_46_DDA_HeLa01`, ProteomeXchange
[PXD066485](https://proteomecentral.proteomexchange.org/cgi/GetDataset?ID=PXD066485)),
converted to mzML. The 50-spectrum example bundled with CasanovoGUI is an excerpt
of this run. Each device sequences the whole file with Casanovo's default
parameters, so the numbers are what a user would see for a run of this size
without any tuning.

## Latest results

<!-- BENCHMARK:BEGIN -->
- **Input:** Loo_20240205_BoAI_46_DDA_HeLa01.mzML
- **Operating system:** Windows 11 (build 26200)
- **CPU:** Intel(R) Core(TM) Ultra 7 265K (20 cores / 20 threads)
- **System memory:** 127 GB
- **GPU:** NVIDIA RTX 5000 Ada Generation (driver 595.95)
- **PyTorch:** 2.5.1+cu121 (CUDA 12.1)
- **Casanovo:** 5.2.1
- **Benchmarked:** 2026-09-01

- **GPU memory baseline before the run:** 0 MiB (per-process accounting unavailable in this driver mode)

| Device | Hardware | MS/MS spectra | Total time | Prediction time | Spectra/s (total) | Spectra/s (prediction) | Peak host RAM | Peak GPU memory | GPU tensors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| GPU | NVIDIA RTX 5000 Ada Generation | 86,311 | 530 s | 380 s | 163 | 227 | 1.71 GB | 20,108 MiB | 2,226 MiB |
| CPU | Intel(R) Core(TM) Ultra 7 265K | 86,311 | 10,926 s | 10,784 s | 8 | 8 | 5.69 GB | n/a | n/a |

*Total time* covers the whole invocation, including model loading and writing results;
*prediction time* is Casanovo's own reported figure, which excludes writing the mzTab.

All memory figures are for the Casanovo process tree alone, not the machine. *Peak host
RAM* is the unique set size, so pages shared between worker processes are counted once.
*Peak GPU memory* is the figure to compare against a card's capacity, since it
includes the CUDA context and the caching allocator's reserved blocks; where the driver
cannot attribute memory per process (WDDM mode on Windows) it is measured as the rise
above the pre-run baseline, which is reported so the reader can see the card was
otherwise idle. *GPU tensors* is Casanovo's own
`torch.cuda.max_memory_allocated()`, which counts live tensor bytes only and is
necessarily smaller.
<!-- BENCHMARK:END -->

## Reproducing the measurement

1. Let CasanovoGUI install Casanovo (start any analysis once), or use your own
   Casanovo environment. The GUI's installation lives at
   `%USERPROFILE%\.casanovo-gui\.venv\Scripts\casanovo.exe` on Windows and
   `~/.casanovo-gui/.venv/bin/casanovo` on macOS and Linux, with a `python`
   executable beside it.
2. Run the script with that environment's Python (it needs `psutil`, which
   Casanovo already depends on):

   ```sh
   python .github/scripts/speed_benchmark.py \
       --input Loo_20240205_BoAI_46_DDA_HeLa01.mzML \
       --casanovo ~/.casanovo-gui/.venv/bin/casanovo \
       --devices gpu cpu --out-dir benchmark
   ```

   In PowerShell, where `\` is not a line continuation:

   ```powershell
   python .github\scripts\speed_benchmark.py `
       --input Loo_20240205_BoAI_46_DDA_HeLa01.mzML `
       --casanovo $env:USERPROFILE\.casanovo-gui\.venv\Scripts\casanovo.exe `
       --devices gpu cpu --out-dir benchmark
   ```

   Use `--devices mps` on Apple Silicon. The CPU run of a full DDA file takes a
   few hours; nothing else should use the machine while it runs, because the
   memory figures are for the Casanovo process alone but the timing is not.
3. The script writes `benchmark/benchmark.md` (the table and machine details) and
   `benchmark/benchmark.json` (the same data for scripts).

## Updating this page for a new release

Run the measurement above on the release's Casanovo version. The next step
overwrites what is on this page, so if the comparison is worth showing, first move
the current block — table and machine details together, since neither means much
without the other — under the "Previous releases" heading below. Then replace
everything between the `BENCHMARK:BEGIN` and `BENCHMARK:END` markers with the
contents of `benchmark.md`.

## Previous releases

None yet: the table above is the first measurement on record.
