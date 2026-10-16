from cloud.domain import BBox


def test_padding_px_uses_short_side_and_pixel_floor():
    box = BBox(0, 0, 636, 98)

    assert box.padding_px(0.05, 8) == 8
    assert box.padding_px(0.2, 8) == 19.6


def test_expand_adds_the_same_pad_on_all_sides_and_clamps_to_image():
    box = BBox(331.75, 911.93, 967.29, 1010.27)

    padded = box.expand(8, 1200, 1600)

    assert padded.as_dict() == {"x1": 323.75, "y1": 903.93, "x2": 975.29, "y2": 1018.27}

    clamped = BBox(2, 3, 10, 12).expand(8, 20, 15)
    assert clamped.as_dict() == {"x1": 0.0, "y1": 0.0, "x2": 18.0, "y2": 15.0}


def test_contains_point_includes_the_boundary():
    box = BBox(332, 912, 967, 1010)

    assert box.contains_point(332, 912) is True
    assert box.contains_point(310, 950) is False
    assert box.contains_point(352, 950) is True
