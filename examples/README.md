# Example data

`hela_50_spectra.mgf` — 50 MS/MS spectra (220 KiB) for checking that a CasanovoGUI
installation works. It sequences in well under a minute on a CPU, so no GPU and no large
download are needed.

In CasanovoGUI: open the **De novo** tab, select this file as the spectrum file, and click
**Run Casanovo**. On the command line:

```
casanovo sequence -o result.mztab hela_50_spectra.mgf
```

A healthy installation sequences all 50 spectra: roughly 11 s on an NVIDIA RTX 5000 Ada and
28 s on a recent desktop CPU. Most of that is loading the model, so the time barely changes
with the number of spectra.

## Where it comes from

Excerpted from a HeLa tryptic digest DDA run acquired on an Orbitrap instrument
(`Loo_20240205_BoAI_46_DDA_HeLa01`, ProteomeXchange: PXD066485); these 50 spectra are a small subset of it.

The 50-spectrum subset in this folder
is distributed as part of CasanovoGUI so that a fresh installation can be verified offline.

## How it was generated

`.github/scripts/extract_test_spectra.py` selected the spectra from a Casanovo 5.2.1 run over
that file (default `casanovo_orbitrap_v5-2-0` weights), keeping only confidently sequenced
ones and sampling them at an even stride across the LC gradient. The selection uses no RNG, so
it reproduces exactly.

Confidence was measured **per residue** rather than by Casanovo's raw peptide score. That score
is the *product* of the per-residue scores, so it falls steeply with peptide length — a 0.9 cut
on it keeps almost nothing longer than a 10-mer and nothing above charge 2. Thresholding its
geometric mean instead (`score^(1/length)` ≥ 0.97) is length-neutral, which is why this set
spans **7–25 residues and charges 2+, 3+ and 4+**.

The peptides Casanovo assigns to these spectra are recorded in
`hela_50_spectra.expected.tsv` and re-checked on Linux, macOS and Windows by
`.github/workflows/smoke.yml`. They are stable across hardware and PyTorch versions:
re-sequencing reproduces 50/50 peptides both on a GPU with PyTorch 2.5.1+cu121 and on a CPU
with PyTorch 2.13.0+cpu.
