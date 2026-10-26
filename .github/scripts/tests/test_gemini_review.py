#!/usr/bin/env python3
"""
Otomatik PR Review Sistemi Kapsamlı Doğrulama Test Paketi
Tamamen yerel, ücretsiz, harici ağ çağrısı gerektirmeyen mock/fixture testleri.
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# Test edilecek script dizinini sys.path'e ekle
SCRIPT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPT_DIR))

from gemini_review import (
    ConfigManager,
    DiffHunk,
    DiffParser,
    FileDiff,
    Finding,
    GeminiReviewer,
    GitHubReviewPublisher,
    ProjectDetector,
    ReviewOrchestrator,
    SecretScanner,
)


class TestProjectDetector(unittest.TestCase):
    """Çok projeli yapı, dinamik proje tespiti ve kök dosyası testleri."""

    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.workspace = Path(self.test_dir)

        # Mock proje ağacı oluştur
        (self.workspace / "backend").mkdir()
        (self.workspace / "backend" / "pom.xml").write_text("<project></project>", encoding="utf-8")

        (self.workspace / "ai").mkdir()
        (self.workspace / "ai" / "requirements.txt").write_text("fastapi==0.100.0", encoding="utf-8")

        (self.workspace / "exercises" / "day-01").mkdir(parents=True)
        (self.workspace / "exercises" / "day-01" / "package.json").write_text('{"name": "day-01"}', encoding="utf-8")

        self.detector = ProjectDetector(workspace_root=self.test_dir)

    def tearDown(self):
        shutil.rmtree(self.test_dir)

    def test_single_project_detection(self):
        """Tek projeli dosya değişikliğini doğru proje kökü ve teknolojiyle eşleştirir."""
        file_path = "backend/account-service/src/main/java/App.java"
        root, tech, manifest = self.detector.detect_project_for_file(file_path)
        self.assertEqual(root, "backend")
        self.assertIn("Java / Spring Boot", tech)
        self.assertEqual(manifest, "pom.xml")

    def test_multi_project_detection(self):
        """Aynı PR'da birden fazla projenin değişmesini başarıyla gruplar."""
        files = [
            "backend/src/main/java/Service.java",
            "ai/app/model.py",
            "backend/pom.xml",
        ]
        groups = self.detector.group_files_by_project(files)
        self.assertIn("backend", groups)
        self.assertIn("ai", groups)
        self.assertEqual(len(groups["backend"].changed_files), 2)
        self.assertEqual(len(groups["ai"].changed_files), 1)

    def test_new_project_dynamic_detection(self):
        """Yeni bir proje klasörü eklendiğinde ek konfigürasyonsuz dinamik olarak tespit eder."""
        file_path = "exercises/day-01/src/App.tsx"
        root, tech, manifest = self.detector.detect_project_for_file(file_path)
        self.assertEqual(root, "exercises/day-01")
        self.assertIn("Node.js / React", tech)
        self.assertEqual(manifest, "package.json")

    def test_root_cross_cutting_change(self):
        """Kök dizindeki workflow veya altyapı değişikliklerini çapraz kapsam olarak işaretler."""
        file_path = ".github/workflows/ci.yml"
        root, tech, manifest = self.detector.detect_project_for_file(file_path)
        self.assertIn("Kök / Çapraz Kapsam", root)
        self.assertEqual(manifest, "repo-root")


class TestSecretScanner(unittest.TestCase):
    """Pre-flight hassas veri (secret) tarama ve maskeleme testleri."""

    def test_detect_and_mask_google_api_key(self):
        secret_line = 'API_KEY = "AIzaSyD-1234567890abcdefghijklmnopqrst"'
        masked, findings = SecretScanner.scan_and_mask("src/config.py", 10, secret_line)
        self.assertNotIn("AIzaSyD", masked)
        self.assertIn("[REDACTED_SECRET_MASKED]", masked)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].severity, "P0")
        self.assertEqual(findings[0].category, "SECURITY")

    def test_detect_github_token(self):
        secret_line = 'export GITHUB_TOKEN="ghp_1234567890abcdefghijklmnopqrstuvwxyz12"'
        masked, findings = SecretScanner.scan_and_mask(".env", 4, secret_line)
        self.assertNotIn("ghp_", masked)
        self.assertIn("[REDACTED_SECRET_MASKED]", masked)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].severity, "P0")

    def test_ignore_dummy_or_mock_keys(self):
        dummy_line = 'MOCK_API_KEY = "AIzaSyD-dummy-example-mock-key-12345"'
        masked, findings = SecretScanner.scan_and_mask("tests/test_api.py", 5, dummy_line)
        self.assertEqual(len(findings), 0)


class TestDiffParser(unittest.TestCase):
    """Diff ayrıştırma ve geçerli sağ taraf (RIGHT) satır eşleştirme testleri."""

    SAMPLE_DIFF = """diff --git a/backend/src/App.java b/backend/src/App.java
new file mode 100644
index 0000000..1111111
--- /dev/null
+++ b/backend/src/App.java
@@ -0,0 +1,5 @@
+package com.example;
+
+public class App {
+    public static void main(String[] args) {}
+}
diff --git a/README.md b/README.md
index 2222222..3333333 100644
--- a/README.md
+++ b/README.md
@@ -10,3 +10,4 @@
 Mevcut satir
+Eklenen satir
"""

    def setUp(self):
        self.config = ConfigManager()

    def test_parse_diff_extracts_files_and_lines(self):
        diffs = DiffParser.parse_unified_diff(self.SAMPLE_DIFF, self.config)
        self.assertEqual(len(diffs), 2)

        app_diff = diffs[0]
        self.assertEqual(app_diff.file_path, "backend/src/App.java")
        self.assertEqual(app_diff.status, "added")
        # 1'den 5'e kadar geçerli satırlar
        self.assertEqual(app_diff.valid_right_lines, {1, 2, 3, 4, 5})

        readme_diff = diffs[1]
        self.assertEqual(readme_diff.file_path, "README.md")
        self.assertIn(11, readme_diff.valid_right_lines)

    def test_excluded_files_are_filtered(self):
        diff_with_lock = """diff --git a/package-lock.json b/package-lock.json
index 111..222 100644
--- a/package-lock.json
+++ b/package-lock.json
@@ -1,2 +1,3 @@
+{ "name": "lock" }
"""
        diffs = DiffParser.parse_unified_diff(diff_with_lock, self.config)
        self.assertEqual(len(diffs), 0)


class TestGitHubReviewPublisher(unittest.TestCase):
    """GitHub API entegrasyonu, stale SHA, mükerrer kontrolü ve inline ayrıştırma."""

    def setUp(self):
        self.publisher = GitHubReviewPublisher(
            token="fake-token",
            repo="test/fullstack-excercises",
            pr_number="42",
            head_sha="abcdef1234567890",
            dry_run=True,
        )

    def test_stale_commit_guard(self):
        """PR'ın head_sha'sı değiştiğinde eski incelemenin yayınlanmasını engeller."""
        with patch.object(self.publisher, "_http_request", return_value=(200, {"head": {"sha": "new_sha_9999"}})):
            self.publisher.dry_run = False
            is_stale = self.publisher.is_stale_commit()
            self.assertTrue(is_stale)

    def test_idempotency_marker_check(self):
        """Aynı commit için önceden review yapılmışsa mükerrer incelemeyi tespit eder."""
        existing_reviews = [
            {"body": "<!-- gemini-pr-review:abcdef1234567890 -->\nÖnceki inceleme özeti."}
        ]
        with patch.object(self.publisher, "_http_request", return_value=(200, existing_reviews)):
            self.publisher.dry_run = False
            self.assertTrue(self.publisher.is_already_reviewed())

    def test_out_of_diff_line_handling(self):
        """Diff dışı kalan satır referansları inline yerine PR özetine eklenir."""
        out_of_diff_finding = Finding(
            id="f-1",
            file_path="src/Legacy.java",
            line_number=999,
            title="Eski Kod Uyarısı",
            severity="P2",
            confidence="HIGH",
            category="CODE_QUALITY",
            where="src/Legacy.java:999",
            condition="Diff dışında kalan eski metot",
            impact="Refactor gerektirir",
            remediation="Güncelleyin",
            verification_test="Unit test",
            is_inline=False,
        )

        summary_md = self.publisher.build_summary_markdown(
            summary_data={"status": "YELLOW", "short_summary": "Test özeti", "affected_projects_summary": "Test"},
            projects={},
            all_findings=[out_of_diff_finding],
            out_of_diff_findings=[out_of_diff_finding],
            status_note="Tamamlandı",
        )

        self.assertIn("Dosya Düzeyi ve Diff Dışı Bulgular", summary_md)
        self.assertIn("Eski Kod Uyarısı", summary_md)


class TestPromptInjectionAndModelHandling(unittest.TestCase):
    """Prompt injection izolasyonu ve model hata senaryoları."""

    def test_prompt_injection_isolation(self):
        """Koddaki 'ignore previous instructions' ifadesi sistem talimatı olarak yorumlanamaz."""
        reviewer = GeminiReviewer(api_key="test-key", config=ConfigManager())
        sys_prompt = reviewer.build_system_prompt("fullstack-excercises", {})
        self.assertIn("GÜVENİLMEYEN GİRDİ (PROMPT INJECTION SAVUNMASI)", sys_prompt)
        self.assertIn("ignore previous instructions", sys_prompt)

    def test_invalid_json_handling(self):
        """Model geçersiz veya bozuk JSON ürettiğinde güvenli şekilde None döner."""
        reviewer = GeminiReviewer(api_key="test-key", config=ConfigManager())
        parsed = reviewer._parse_and_validate_json("Bu bir JSON degildir.")
        self.assertIsNone(parsed)

    def test_valid_json_with_markdown_ticks(self):
        """Model ```json ... ``` etiketleri eklediğinde temizlenip doğru ayrıştırılır."""
        reviewer = GeminiReviewer(api_key="test-key", config=ConfigManager())
        raw = """```json
{
  "summary": {
    "status": "GREEN",
    "short_summary": "Temiz kod.",
    "affected_projects_summary": "Sorun yok."
  },
  "findings": []
}
```"""
        parsed = reviewer._parse_and_validate_json(raw)
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["summary"]["status"], "GREEN")


class TestReviewOrchestrator(unittest.TestCase):
    """Uçtan uca mock orkestrasyon ve fork PR davranışı."""

    def setUp(self):
        self.orchestrator = ReviewOrchestrator(dry_run=True)

    def test_missing_secret_and_fork_pr(self):
        """Secret eksik veya Fork PR olduğunda güvenli bilgilendirme yapılır."""
        exit_code = self.orchestrator.run(
            workspace_root=".",
            base_sha="base123",
            head_sha="head123",
            pr_number="10",
            repo="Firatasi/fullstack-excercises",
            gemini_api_key="",
            github_token="fake-token",
        )
        self.assertEqual(exit_code, 0)

    def test_end_to_end_mock_run(self):
        """Mock Gemini yanıtı ile uçtan uca dry-run incelemesi."""
        diff = """diff --git a/backend/src/User.java b/backend/src/User.java
new file mode 100644
index 0000000..1111111
--- /dev/null
+++ b/backend/src/User.java
@@ -0,0 +1,5 @@
+public class User {
+    private String name;
+    private String password;
+}
"""
        mock_response = {
            "summary": {
                "status": "YELLOW",
                "short_summary": "Kullanıcı modelinde parola saklama alanı eklendi.",
                "affected_projects_summary": "backend servisi güncellendi.",
            },
            "findings": [
                {
                    "id": "f-user-1",
                    "file_path": "backend/src/User.java",
                    "line_number": 3,
                    "title": "Düz Metin Parola Saklama Riski",
                    "severity": "P1",
                    "confidence": "HIGH",
                    "category": "SECURITY",
                    "where": "backend/src/User.java (Satır 3)",
                    "condition": "Parolanın hash'lenmeden nesnede tutulması",
                    "impact": "Bellek dökümünde veya loglarda parola sızıntısı",
                    "remediation": "Parolayı BCrypt/Argon2 ile hash'leyin",
                    "verification_test": "UserTest birim testi",
                }
            ],
        }

        exit_code = self.orchestrator.run(
            workspace_root=".",
            base_sha="base123",
            head_sha="head123",
            pr_number="15",
            repo="Firatasi/fullstack-excercises",
            gemini_api_key="mock-key",
            github_token="fake-token",
            diff_override=diff,
            mock_model_response=mock_response,
        )
        self.assertEqual(exit_code, 0)


if __name__ == "__main__":
    unittest.main()
