"""측정 폴더 읽기 — 실제 코드는 앱 엔진 폴더의 pipeflow_records.py (앱의 엑셀 내보내기와 같은 코드)"""
import os
import sys

_ENGINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android-app", "app", "src", "main", "python")
if _ENGINE not in sys.path:
    sys.path.insert(0, _ENGINE)

from pipeflow_records import ANNOTATION_FIELDS, differs, load_json, read_session, waterline  # noqa: E402,F401
