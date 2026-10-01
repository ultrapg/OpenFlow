#!/bin/bash
set -e

echo "Lade Sherpa-ONNX Bibliothek herunter..."
mkdir -p app/libs
curl -L -o app/libs/sherpa-onnx-1.13.8.aar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar

echo "Erstelle Modell-Ordnerstruktur..."
mkdir -p app/src/main/assets/models/lid
cd app/src/main/assets/models

echo "Lade NVIDIA Canary 180M (int8) herunter..."
curl -L -o canary.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2
tar -xjf canary.tar.bz2
rm canary.tar.bz2
rm -rf nemo-canary-180m
mv sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8 nemo-canary-180m

echo "Lade Whisper Tiny (LID) herunter..."
cd lid
curl -L -o whisper.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2
tar -xjf whisper.tar.bz2
rm whisper.tar.bz2
rm -rf sherpa-onnx-whisper-tiny/tiny-decoder.onnx
rm -rf sherpa-onnx-whisper-tiny/tiny-encoder.onnx
rm -rf sherpa-onnx-whisper-tiny/tiny-tokens.txt
rm -rf sherpa-onnx-whisper-tiny/test_wavs

echo "Alle Abhängigkeiten und Modelle wurden erfolgreich heruntergeladen!"
