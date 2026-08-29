#!/usr/bin/env python3
"""Build a small, high-confidence MGF for testing CasanovoGUI.

Takes a Casanovo mzTab result and the mzML it came from, keeps the PSMs whose peptide-level
score clears a threshold, and writes out that many spectra as an MGF plus a manifest of what
Casanovo called them.

Why this shape:

* **Confident spectra only.** Casanovo's peptide score is the product of its per-residue
  scores, so a high value means every residue was called confidently. Spectra above ~0.9 are
  ones a correct installation should re-sequence to the same peptide, which turns the MGF from
  a "does it crash" fixture into a regression fixture.
* **Spread across the gradient, not the top 50.** Candidates are sampled at an even stride over
  the run rather than taken in score order, so the file covers the whole LC gradient and a
  realistic mixture of charges and peptide lengths instead of one easy corner of the data.
* **Deterministic.** No RNG: the same inputs always produce the same MGF, so a regenerated
  fixture does not silently churn.
* **Already sequenced, so already valid.** Every selected spectrum passed Casanovo's own
  filters (min_peaks, max_charge, precursor tolerance) during the source run, so the fixture
  cannot contain spectra Casanovo would skip.

The companion `.tsv` records the peptide, score and per-residue scores Casanovo assigned to
each spectrum, so a test can assert re-sequencing reproduces them.

Usage
-----
    python extract_test_spectra.py \\
        --mztab  Loo_20240205_BoAI_46_DDA_HeLa01/casanovo_20260826152841.mztab \\
        --mzml   Loo_20240205_BoAI_46_DDA_HeLa01/Loo_20240205_BoAI_46_DDA_HeLa01.mzML \\
        --out    example_50_spectra.mgf

Requires pyteomics.
"""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass
from pathlib import Path

try:
    from pyteomics import mzml
except ImportError:  # pragma: no cover - a setup problem, not a logic one
    sys.exit("pyteomics is required: pip install pyteomics lxml")


# mzTab PSM columns are located by name from the PSH header, so a change in column order
# between Casanovo versions does not break this script.
SEQUENCE_COL = "sequence"
SCORE_COL = "search_engine_score[1]"
SPECTRA_REF_COL = "spectra_ref"
CHARGE_COL = "charge"
EXP_MZ_COL = "exp_mass_to_charge"
AA_SCORES_COL = "opt_global_aa_scores"


@dataclass
class Psm:
    """One Casanovo PSM, with the mzML spectrum id it refers to."""

    spectrum_id: str
    scan: int
    sequence: str
    score: float
    charge: int
    exp_mz: float
    aa_scores: str


def parse_mztab(path: Path) -> list[Psm]:
    """Read the PSM section of a Casanovo mzTab."""
    psms: list[Psm] = []
    columns: dict[str, int] | None = None

    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("PSH\t"):
                header = line.rstrip("\n").split("\t")
                columns = {name.strip(): i for i, name in enumerate(header)}
                missing = [c for c in (SEQUENCE_COL, SCORE_COL, SPECTRA_REF_COL)
                           if c not in columns]
                if missing:
                    sys.exit(f"mzTab PSM header is missing {missing}")
                continue

            if columns is None or not line.startswith("PSM\t"):
                continue

            cells = line.rstrip("\n").split("\t")

            def cell(name: str) -> str:
                index = columns.get(name, -1)
                return cells[index] if 0 <= index < len(cells) else ""

            try:
                score = float(cell(SCORE_COL))
            except ValueError:
                continue  # 'null' or malformed: not a usable PSM

            # e.g. "ms_run[1]:controllerType=0 controllerNumber=1 scan=5" -> the part after
            # the first colon is the spectrum's native id in the mzML.
            ref = cell(SPECTRA_REF_COL)
            _, _, spectrum_id = ref.partition(":")
            if not spectrum_id:
                continue
            try:
                scan = int(spectrum_id.rsplit("scan=", 1)[1])
            except (IndexError, ValueError):
                continue

            try:
                charge = int(float(cell(CHARGE_COL)))
            except ValueError:
                charge = 0
            try:
                exp_mz = float(cell(EXP_MZ_COL))
            except ValueError:
                exp_mz = 0.0

            psms.append(Psm(spectrum_id, scan, cell(SEQUENCE_COL), score,
                            charge, exp_mz, cell(AA_SCORES_COL)))

    if columns is None:
        sys.exit(f"No PSM section (PSH header) found in {path.name}")
    return psms


def confidence(psm: Psm, score_type: str) -> float:
    """The confidence measure to threshold on.

    Casanovo's peptide score is the *product* of the per-residue scores, so it falls off
    steeply with peptide length: on this dataset a 0.9 cut keeps almost nothing longer than a
    10-mer and almost nothing above charge 2. The per-residue alternative is that product's
    geometric mean, score**(1/length), which reads as "average confidence per residue" and is
    therefore comparable across lengths.
    """
    if score_type == "per-residue":
        length = len(psm.sequence)
        return psm.score ** (1.0 / length) if length else 0.0
    return psm.score


def select(psms: list[Psm], count: int, min_score: float,
           min_length: int, max_length: int, max_charge: int,
           score_type: str = "peptide") -> list[Psm]:
    """Pick `count` confident PSMs spread evenly across the run."""
    candidates = [
        p for p in psms
        if confidence(p, score_type) >= min_score
        and min_length <= len(p.sequence) <= max_length
        and 0 < p.charge <= max_charge
    ]
    if len(candidates) < count:
        sys.exit(f"Only {len(candidates)} PSMs pass the filters; asked for {count}. "
                 f"Lower --min-score or --n.")

    candidates.sort(key=lambda p: p.scan)
    # Even stride over acquisition order: covers the whole gradient rather than clustering
    # wherever the highest scores happen to fall.
    stride = len(candidates) / count
    picked = [candidates[min(int(i * stride), len(candidates) - 1)] for i in range(count)]

    # A stride can land twice on the same spectrum only if count > len(candidates), which is
    # excluded above; assert it anyway so a future edit cannot silently emit duplicates.
    seen = {p.spectrum_id for p in picked}
    assert len(seen) == count, "selection produced duplicate spectra"
    return picked


def retention_seconds(spectrum: dict) -> float:
    """Scan start time in seconds, whatever unit the mzML recorded it in."""
    try:
        scan = spectrum["scanList"]["scan"][0]
        value = scan["scan start time"]
    except (KeyError, IndexError):
        return 0.0
    unit = getattr(value, "unit_info", None)
    return float(value) * 60.0 if unit == "minute" else float(value)


def precursor(spectrum: dict) -> tuple[float, int, float]:
    """(m/z, charge, intensity) of the selected precursor ion; zeros when absent."""
    try:
        ion = spectrum["precursorList"]["precursor"][0]["selectedIonList"]["selectedIon"][0]
    except (KeyError, IndexError):
        return 0.0, 0, 0.0
    return (float(ion.get("selected ion m/z", 0.0)),
            int(ion.get("charge state", 0) or 0),
            float(ion.get("peak intensity", 0.0) or 0.0))


def write_mgf(out_path: Path, spectra: list[tuple[Psm, dict]]) -> None:
    """Write the selected spectra as MGF, in acquisition order."""
    with out_path.open("w", encoding="utf-8", newline="\n") as handle:
        for psm, spectrum in spectra:
            mz, charge, intensity = precursor(spectrum)
            # Fall back to the mzTab's values if the mzML omitted them.
            mz = mz or psm.exp_mz
            charge = charge or psm.charge

            handle.write("BEGIN IONS\n")
            handle.write(f"TITLE={psm.spectrum_id}\n")
            handle.write(f"PEPMASS={mz:.6f}" + (f" {intensity:.1f}\n" if intensity else "\n"))
            handle.write(f"CHARGE={charge}+\n")
            handle.write(f"RTINSECONDS={retention_seconds(spectrum):.3f}\n")
            handle.write(f"SCANS={psm.scan}\n")
            for peak_mz, peak_intensity in zip(spectrum["m/z array"],
                                               spectrum["intensity array"]):
                handle.write(f"{peak_mz:.5f} {peak_intensity:.1f}\n")
            handle.write("END IONS\n")


def write_manifest(path: Path, spectra: list[tuple[Psm, dict]]) -> None:
    """Record what Casanovo called each spectrum, so a test can assert it reproduces them.

    The `mgf_index` column is the join key. Sequencing an MGF makes Casanovo report
    `spectra_ref` as `ms_run[1]:index=N`, the 0-based position in the file — it does not carry
    the original scan number through — so a test comparing a rerun against this manifest must
    join on that index. `scan` and `spectrum_id` are kept for tracing a spectrum back to the
    source mzML, not for joining.
    """
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(["mgf_index", "scan", "spectrum_id", "charge", "precursor_mz",
                         "peptide", "peptide_length", "peptide_score",
                         "per_residue_score", "aa_scores"])
        for index, (psm, _) in enumerate(spectra):
            writer.writerow([index, psm.scan, psm.spectrum_id, psm.charge,
                             f"{psm.exp_mz:.6f}", psm.sequence, len(psm.sequence),
                             f"{psm.score:.6f}",
                             f'{confidence(psm, "per-residue"):.6f}', psm.aa_scores])


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mztab", required=True, type=Path, help="Casanovo mzTab result")
    parser.add_argument("--mzml", required=True, type=Path, help="the mzML it was produced from")
    parser.add_argument("--out", required=True, type=Path, help="MGF to write")
    parser.add_argument("--n", type=int, default=50, help="how many spectra (default: 50)")
    parser.add_argument("--min-score", type=float, default=0.9,
                        help="minimum confidence (default: 0.9)")
    parser.add_argument("--score-type", choices=("peptide", "per-residue"), default="peptide",
                        help="which confidence to threshold: 'peptide' is Casanovo's own score, "
                             "the product of the per-residue scores, which falls with peptide "
                             "length; 'per-residue' is its geometric mean, score**(1/length), "
                             "which does not (default: peptide)")
    # Defaults mirror Casanovo's own config so the fixture cannot contain spectra it would skip.
    parser.add_argument("--min-length", type=int, default=6,
                        help="minimum peptide length (default: 6, Casanovo's min_peptide_len)")
    parser.add_argument("--max-length", type=int, default=100,
                        help="maximum peptide length (default: 100, Casanovo's max_peptide_len)")
    parser.add_argument("--max-charge", type=int, default=4,
                        help="maximum precursor charge (default: 4, Casanovo's max_charge)")
    args = parser.parse_args()

    for required in (args.mztab, args.mzml):
        if not required.is_file():
            sys.exit(f"Not found: {required}")

    print(f"Reading {args.mztab.name} ...")
    psms = parse_mztab(args.mztab)
    print(f"  {len(psms):,} PSMs")

    picked = select(psms, args.n, args.min_score,
                    args.min_length, args.max_length, args.max_charge, args.score_type)
    eligible = sum(1 for p in psms if confidence(p, args.score_type) >= args.min_score)
    print(f"  {eligible:,} at {args.score_type} score >= {args.min_score}"
          f"; selected {len(picked)} spread across scans "
          f"{picked[0].scan}-{picked[-1].scan}")

    print(f"Reading spectra from {args.mzml.name} ...")
    spectra: list[tuple[Psm, dict]] = []
    with mzml.MzML(str(args.mzml)) as reader:
        for psm in picked:
            try:
                spectrum = reader.get_by_id(psm.spectrum_id)
            except KeyError:
                sys.exit(f"Spectrum {psm.spectrum_id!r} is not in {args.mzml.name}")
            spectra.append((psm, spectrum))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    write_mgf(args.out, spectra)
    manifest = args.out.with_suffix(".expected.tsv")
    write_manifest(manifest, spectra)

    peaks = sum(len(s["m/z array"]) for _, s in spectra)
    scores = [p.score for p, _ in spectra]
    per_residue = [confidence(p, "per-residue") for p, _ in spectra]
    lengths = [len(p.sequence) for p, _ in spectra]
    charges = sorted({p.charge for p, _ in spectra})
    print()
    print(f"Wrote {args.out}  ({args.out.stat().st_size / 1024:.1f} KiB)")
    print(f"      {manifest.name}")
    print(f"  spectra          : {len(spectra)}")
    print(f"  peaks            : {peaks:,} (mean {peaks / len(spectra):.0f} per spectrum)")
    print(f"  peptide score    : {min(scores):.3f} - {max(scores):.3f}")
    print(f"  per-residue score: {min(per_residue):.3f} - {max(per_residue):.3f}")
    print(f"  peptide length   : {min(lengths)} - {max(lengths)}")
    print(f"  precursor charge : {', '.join(str(c) for c in charges)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
