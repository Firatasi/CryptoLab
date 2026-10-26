import sys
from pathlib import Path

ai_dir = Path(__file__).resolve().parent
if str(ai_dir) not in sys.path:
    sys.path.insert(0, str(ai_dir))

