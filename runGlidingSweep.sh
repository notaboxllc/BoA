#!/usr/bin/env bash
# runGlidingSweep.sh — density-sweep batch runner for BoxOfActin gliding assay.
# Run from the BoA repo root.  Each density point is a separate JVM invocation.
# Output: one parent directory containing 8 subdirs, each with frame JSONs,
# gliding_assay.dat, and params.txt.
set -uo pipefail

usage() {
  echo "Usage: $0 [-o <parentDir>] [-p <templateParamFile>] [-t <runTimeSeconds>] [-h]"
  echo "  -o  Parent batch directory (default: ~/Desktop/gliding_batch)"
  echo "  -p  Template parameter file (default: ParameterFiles/glidingAssayBatch_template)"
  echo "  -t  Simulation time per density point in seconds (default: 4.0)"
  echo "  -h  Show this help"
  echo ""
  echo "Densities: 10, 25, 50, 100, 200, 500, 1000, 2500 motors/µm²"
  echo "Run from the BoA repo root."
}

# Defaults
parent_dir=~/Desktop/gliding_batch
template_file=ParameterFiles/glidingAssayBatch_template
run_time=4.0
densities=(10 25 50 100 200 500 1000 2500)

while [[ $# -gt 0 ]]; do
  case $1 in
    -o) parent_dir="$2"; shift 2 ;;
    -p) template_file="$2"; shift 2 ;;
    -t) run_time="$2"; shift 2 ;;
    -h) usage; exit 0 ;;
    *)  echo "Unknown argument: $1"; usage; exit 1 ;;
  esac
done

# Expand leading tilde manually (needed when parent_dir came from -o argument)
parent_dir="${parent_dir/#\~/$HOME}"

# Verify template exists before doing anything else
if [[ ! -f "$template_file" ]]; then
  echo "Error: template file not found: $template_file"
  exit 1
fi

# Verify we're in the BoA repo root
if [[ ! -f "BoxOfActin.java" ]]; then
  echo "Error: BoxOfActin.java not found. Run this script from the BoA repo root."
  exit 1
fi

# Auto-increment parent directory if it already exists
if [[ -e "$parent_dir" ]]; then
  base="$parent_dir"
  i=1
  while [[ -e "${base}.$(printf '%03d' $i)" ]]; do
    ((i++))
  done
  parent_dir="${base}.$(printf '%03d' $i)"
  echo "Parent directory already exists; using: $parent_dir"
fi

mkdir -p "$parent_dir" || { echo "Error: could not create $parent_dir"; exit 1; }
echo "Batch output: $parent_dir"
echo "Run time per density: ${run_time}s"
echo "Densities: ${densities[*]} motors/µm²"
echo ""

failed_densities=()

for density in "${densities[@]}"; do
  echo "[density ${density}] starting..."
  t0=$(date +%s)

  # Generate per-density parameter file alongside the parent dir
  param_file="${parent_dir}/params_${density}.txt"
  sed -e "s/__DENSITY__/${density}/g" \
      -e "s/__RUNTIME__/${run_time}/g" \
      "$template_file" > "$param_file"

  # subdir path passed to -3js  (BoA creates it; must not pre-exist)
  subdir="${parent_dir}/density_${density}"

  if java -Xmx800M -cp ".:libs/*" BoxOfActin -r \
       -pf "$param_file" \
       -3js "$subdir"; then
    elapsed=$(( $(date +%s) - t0 ))
    # copy params into the output subdir that BoA just created
    [[ -d "$subdir" ]] && cp "$param_file" "${subdir}/params.txt"
    rm -f "$param_file"
    echo "[density ${density}] done in ${elapsed}s."
  else
    elapsed=$(( $(date +%s) - t0 ))
    echo "[density ${density}] FAILED after ${elapsed}s."
    failed_densities+=("$density")
    rm -f "$param_file"
  fi
done

echo ""
echo "================================================================"
echo "Batch complete.  Output: ${parent_dir}"
echo "Densities run: ${densities[*]} motors/µm²"
echo "Each subdirectory contains: frame JSONs, gliding_assay.dat, params.txt"
if [[ ${#failed_densities[@]} -gt 0 ]]; then
  echo "WARNING: failed densities: ${failed_densities[*]}"
fi
echo "================================================================"
