import unittest

from schemas import AnalyzeResponse


class AnalyzeResponseContractTests(unittest.TestCase):
    def test_analyze_response_defaults_to_guangdong_profile(self):
        response = AnalyzeResponse(
            user_hand=["1m", "2m"],
            melded_tiles=[],
            suggested_play="discard 1m",
        )

        self.assertEqual(response.rule_profile, "guangdong")
        self.assertIsNone(response.recognition_confidence)
        self.assertIsNone(response.suggestion_confidence)
        self.assertIsNone(response.warning)
        self.assertTrue(response.is_stable)


    def test_analyze_response_accepts_protocol_alignment_fields(self):
        response = AnalyzeResponse(
            user_hand=["1m", "2m"],
            melded_tiles=["3p", "3p", "3p"],
            suggested_play="discard 1m",
            rule_profile="custom-demo",
            recognition_confidence=0.82,
            suggestion_confidence=0.74,
            warning="demo warning",
            is_stable=False,
        )

        payload = response.model_dump() if hasattr(response, "model_dump") else response.dict()
        self.assertEqual(payload["rule_profile"], "custom-demo")
        self.assertEqual(payload["recognition_confidence"], 0.82)
        self.assertEqual(payload["suggestion_confidence"], 0.74)
        self.assertEqual(payload["warning"], "demo warning")
        self.assertFalse(payload["is_stable"])


if __name__ == "__main__":
    unittest.main()
