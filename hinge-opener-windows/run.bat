@echo off
rem Double-click to start Hinge Opener. First run creates a private Python environment.
cd /d "%~dp0"
if not exist .venv (
    echo Setting up for the first time...
    py -3 -m venv .venv || python -m venv .venv
    .venv\Scripts\python -m pip install --upgrade pip >nul
    .venv\Scripts\python -m pip install -r requirements.txt
)
start "" .venv\Scripts\pythonw hinge_opener.py
