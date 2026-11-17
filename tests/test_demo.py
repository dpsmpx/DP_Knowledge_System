import unittest
from pathlib import Path

from ksys.demo.scenarios import run_all

GOLDEN = Path(__file__).resolve().parents[1] / "demo" / "DEMO_OUTPUT.md"


class DemoGoldenTest(unittest.TestCase):
    """Демо детерминировано: вывод совпадает с зафиксированным golden-файлом байт-в-байт.
    Намеренное изменение поведения: `bin/knowledge demo --out demo/DEMO_OUTPUT.md` и ревью diff."""

    def test_demo_output_is_golden(self):
        self.assertEqual(run_all(), GOLDEN.read_text(encoding="utf-8"))

    def test_all_ten_scenarios_present(self):
        text = GOLDEN.read_text(encoding="utf-8")
        for k in range(1, 11):
            self.assertIn(f"## Сценарий {k}.", text)


if __name__ == "__main__":
    unittest.main()
