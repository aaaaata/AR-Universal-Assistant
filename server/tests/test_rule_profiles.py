import unittest

from rule_profiles import (
    DEFAULT_RULE_PROFILE,
    DEFAULT_RULE_PROFILE_ID,
    normalize_rule_profile_id,
    validate_rule_profile_config,
)


class RuleProfileTests(unittest.TestCase):
    def test_default_rule_profile_is_guangdong(self):
        self.assertEqual(DEFAULT_RULE_PROFILE_ID, "guangdong")
        self.assertEqual(DEFAULT_RULE_PROFILE.id, "guangdong")
        self.assertEqual(DEFAULT_RULE_PROFILE.suggestion["strategy"], "hand_efficiency")

    def test_normalize_rule_profile_id(self):
        cases = [
            (None, "guangdong"),
            ("", "guangdong"),
            ("  ", "guangdong"),
            ("sichuan", "sichuan"),
            (" custom-demo ", "custom-demo"),
        ]

        for value, expected in cases:
            with self.subTest(value=value):
                self.assertEqual(normalize_rule_profile_id(value), expected)

    def test_validate_rule_profile_config_accepts_declarative_custom_rules(self):
        profile = validate_rule_profile_config(
            {
                "id": "sichuan",
                "name": "Sichuan MVP",
                "winning": {"patterns": ["standard_4_melds_1_pair"]},
                "scoring": {"mode": "custom"},
                "suggestion": {"strategy": "hand_efficiency"},
            }
        )

        self.assertEqual(profile.id, "sichuan")
        self.assertEqual(profile.winning["patterns"], ["standard_4_melds_1_pair"])

    def test_validate_rule_profile_config_rejects_executable_fields(self):
        with self.assertRaisesRegex(ValueError, "executable"):
            validate_rule_profile_config(
                {
                    "id": "unsafe",
                    "name": "Unsafe",
                    "winning": {"script": "return true"},
                }
            )


if __name__ == "__main__":
    unittest.main()
