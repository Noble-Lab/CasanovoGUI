# CasanovoGUI

![Downloads](https://img.shields.io/github/downloads/Noble-Lab/CasanovoGUI/total.svg) ![Release](https://img.shields.io/github/release/Noble-Lab/CasanovoGUI.svg)![Downloads](https://img.shields.io/github/downloads/Noble-Lab/CasanovoGUI/latest/total)

CasanovoGUI is a cross-platform desktop application for
[Casanovo](https://github.com/Noble-Lab/casanovo) — *de novo* peptide sequencing
from MS/MS spectra with a transformer model. Configure your inputs and parameters
in a simple form, click **Run Casanovo**, and watch the output stream live in the
console. The GUI wraps Casanovo's command-line sub-commands — *de novo* sequencing,
database search, evaluation, and training — each as its own tab, plus a **View** tab
to explore your results: map peptides to a reference proteome, inspect score
distributions and per-residue confidence, and open spectra in
[PDV](https://github.com/wenbostar/PDV) for annotated-spectrum visualization.

## Installation

Download the installer for your platform from the
[**Releases page**](https://github.com/Noble-Lab/CasanovoGUI/releases/latest):

| OS | Download | How to run |
|----|----------|------------|
| **Windows** | `CasanovoGUI-<version>-windows-x64.zip` | Unzip and run `CasanovoGUI.exe` |
| **macOS** (Apple Silicon) | `CasanovoGUI-<version>-macos-arm64.dmg` | Open and drag to Applications |
| **Linux** | `CasanovoGUI-<version>-linux-x86_64.deb` or `…-linux-x86_64.tar.gz` | Install the `.deb`, or just extract the `.tar.gz` (no root) and run |

**No manual tool installation is needed.** The installers bundle their own Java
runtime, so you do **not** have to install Java. And the first time you start an
analysis, if Casanovo isn't already on your machine the GUI offers to **install it
for you** — it downloads a private Python and Casanovo automatically (no Python or
`pip` setup required). Just download, launch, and run.

> **Intel Macs:** use the cross-platform `CasanovoGUI-<version>.jar` from the
> Releases page instead (it needs a Java 23+ runtime installed).

## The *De novo* panel

<img src="docs/images/CasanovoGUI.png" alt="CasanovoGUI" width="70%">

The **De novo** tab runs `casanovo sequence` to predict peptide sequences directly
from your spectra. Its main input settings are:

- **Spectrum file(s)** *(required)* — one or more **mzML**, **mzXML**, or **MGF**
  files to sequence. You can select several at once.
- **Model weights (`--model`)** *(optional)* — a `.ckpt` model file. Leave it blank
  to use Casanovo's cached default weights, which the GUI detects and shows for you.
- **Config file (`--config`)** *(optional)* — a YAML file that overrides parameters.
  Leave it blank to use the values from the **Parameters** dialog instead.
- **Output directory** and **output root name** — where the result `.mztab` and log
  are written, and the base name used for them.
- **Verbosity** and **Overwrite existing output files** — the logging level and
  whether to overwrite a previous run in the same folder (on by default).

Click **Parameters** to fine-tune any Casanovo setting (precursor m/z tolerance,
peptide length, number of beams, batch size, accelerator, and more) without editing
YAML by hand, and the live **command preview** shows the exact `casanovo …` command
that will run.

## The View panel

The **View** tab opens a Casanovo `.mzTab` result so you can explore it without
leaving the GUI:

- **Map to a proteome** *(optional)* — point it at a reference **FASTA** and it maps
  every de novo peptide back to your proteins (via
  [pepmap](https://github.com/wenbostar/pepmap), auto-downloaded on first use). You
  get an **Overview** with score-distribution, mapping-vs-cutoff, and top-protein
  charts, plus **Proteins**, **Mapped**, and **Unmapped** tables and a per-protein
  coverage map. Leave the FASTA blank to skip mapping and just browse the peptides
  and their scores.
- **Per-residue confidence** — double-click a peptide to open a residue-by-residue
  confidence track and a table of all its PSMs (sorted by peptide score).
- **Drive PDV** — with [PDV](https://github.com/wenbostar/PDV) open, clicking a
  peptide selects its best PSM and renders the annotated spectrum (requires PDV
  2.5.0+).


