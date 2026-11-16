"""Writes clips/*.json, the golden retriever's Lottie clips. Then run render.py.

    python tools/hyper-pet/make_dog.py

Every clip is the same dog idling (bob, pant, blinks) for 15 s with one "moment" in the middle.
The app posts the island again every 15 s, each time with another random clip.
"""
import json
from pathlib import Path

OP = 900  # 15 s at 60 fps; HyperPetPill.CLIP_MS must match

GOLD, EAR, CREAM = [0.96, 0.73, 0.36], [0.82, 0.53, 0.22], [1.0, 0.91, 0.72]
DARK, PINK, WHITE = [0.24, 0.14, 0.11], [1.0, 0.5, 0.58], [1.0, 1.0, 1.0]
MOUTH, BONE, WING = [0.45, 0.12, 0.16], [1.0, 0.97, 0.9], [0.62, 0.55, 1.0]


def static(v):
    return {"a": 0, "k": v}


def anim(keys):
    out = []
    for i, (t, v) in enumerate(keys):
        v = v if isinstance(v, list) else [v]
        k = {"t": t, "s": v}
        if i < len(keys) - 1:
            k["i"] = {"x": [0.5] * len(v), "y": [1] * len(v)}
            k["o"] = {"x": [0.5] * len(v), "y": [0] * len(v)}
        out.append(k)
    return {"a": 1, "k": out}


def ks(p=None, a=None, s=None, r=None, o=None):
    return {"o": o or static(100), "r": r or static(0), "p": p or static([0, 0, 0]),
            "a": a or static([0, 0, 0]), "s": s or static([100, 100, 100])}


def tr(p=None, a=None, r=None, s=None):
    return {"ty": "tr", "p": p or static([0, 0]), "a": a or static([0, 0]), "s": s or static([100, 100]),
            "r": r or static(0), "o": static(100)}


def el(p, s):
    return {"ty": "el", "p": static(p), "s": static(s)}


def fill(color):
    return {"ty": "fl", "c": static(color + [1]), "o": static(100)}


def group(name, items, color, transform=None, paint=None):
    return {"ty": "gr", "nm": name, "it": items + [paint or fill(color), transform or tr()]}


# One pose of the dog. Everything is 0 at rest except lid (1 = eyes open).
#   look -1 left / +1 right    down +1 nose to the ground / -1 nose up    tilt: curious head tilt
#   lid: how open the eyes are    happy: squinty smiling eyes    mouth: open, as in a yawn
#   sleep: closed, sleeping eyes (use with lid=0)
#   earflap: ears swung outwards, in degrees    tongue: extra size (-1 hides it)    tonguex: licking
#   bone: size of the bone in its mouth
def P(t, **pose):
    return t, {"look": 0, "down": 0, "tilt": 0, "lid": 1, "happy": 0, "sleep": 0, "mouth": 0, "earflap": 0,
               "tongue": 0, "tonguex": 0, "bone": 0, **pose}


def osc(windows, value):
    """Back-and-forth wobble: windows are (start, end, amplitude, frames per swing)."""
    keys = [(0, value(0))]
    for start, end, amp, period in windows:
        keys.append((start, value(0)))
        keys += [(t, value(amp if i % 2 else -amp)) for i, t in enumerate(range(start + period, end, period))]
        keys.append((end, value(0)))
    return anim(keys + [(OP, value(0))])


def build(name, poses, blinks, sniffs=(), shake=(), nod=(), ear_bounce=(), zs=(), butterfly=None):
    layers = []

    def layer(label, ty, ks_, parent=None, shapes=None):
        item = {"ddd": 0, "ind": len(layers) + 1, "ty": ty, "nm": label, "sr": 1, "ks": ks_, "ao": 0,
                "ip": 0, "op": OP, "st": 0}
        if parent:
            item["parent"] = parent
        if shapes is not None:
            item["shapes"] = shapes
        layers.append(item)
        return item["ind"]

    def pose(f):
        return anim([(t, f(p)) for t, p in poses])

    if butterfly:
        path, flap = butterfly
        layer("Butterfly", 4, ks(
            p=anim([(t, [x, y, 0]) for t, x, y, _ in path]),
            o=anim([(t, o) for t, _, _, o in path])), shapes=[
            group("Body", [el([0, 0], [2, 7])], DARK),
            group("Wings", [el([-4, 0], [7, 9]), el([4, 0], [7, 9])], WING,
                  tr(s=osc([flap], lambda v: [62.5 + v, 100]))),
        ])
    if zs:
        z = {"ty": "sh", "ks": static({"i": [[0, 0]] * 4, "o": [[0, 0]] * 4,
                                       "v": [[-4, -4], [4, -4], [-4, 4], [4, 4]], "c": False})}
        stroke = {"ty": "st", "c": static(WHITE + [1]), "o": static(100), "w": static(2.2), "lc": 2, "lj": 2}
        position, opacity, scale = [(0, [84, 32, 0])], [(0, 0)], [(0, [60, 60, 100])]
        for start in zs:
            position += [(start, [84, 32, 0]), (start + 110, [92, 18, 0])]
            opacity += [(start, 0), (start + 20, 100), (start + 90, 100), (start + 110, 0)]
            scale += [(start, [60, 60, 100]), (start + 110, [100, 100, 100])]
        layer("Zs", 4, ks(p=anim(position + [(OP, [84, 32, 0])]), o=anim(opacity + [(OP, 0)]),
                          s=anim(scale + [(OP, [60, 60, 100])])),
              shapes=[group("Z", [z], WHITE, paint=stroke)])

    # The whole head dips when the dog looks down and lifts when it looks up.
    dip = layer("Dip", 3, ks(p=pose(lambda p: [0, 3 * p["down"], 0])))
    wobble = layer("Shake", 3, ks(p=static([60, 60, 0]), a=static([60, 60, 0]), r=osc(shake, lambda v: v)),
                   parent=dip)
    bounce = layer("Nod", 3, ks(p=osc(nod, lambda v: [0, v, 0])), parent=wobble)
    # A gentle bob, plus a lean towards wherever it is looking.
    head = layer("Head", 3, ks(
        p=anim([(t, [60, 58 if (t // 30) % 2 else 60, 0]) for t in range(0, OP + 1, 30)]),
        a=static([60, 60, 0]),
        r=pose(lambda p: 5 * p["look"] + 11 * p["tilt"])), parent=bounce)
    # The features slide across the head, which is what sells a turn.
    face = layer("Face", 3, ks(p=pose(lambda p: [6 * p["look"], 6 * p["down"], 0])), parent=head)

    layer("Bone", 4, ks(), parent=face, shapes=[group("Bone", [
        {"ty": "rc", "p": static([60, 80]), "s": static([20, 6]), "r": static(3)},
        el([50, 77.5], [8, 8]), el([50, 82.5], [8, 8]), el([70, 77.5], [8, 8]), el([70, 82.5], [8, 8]),
    ], BONE, tr(p=static([60, 80]), a=static([60, 80]), r=static(-8),
                s=pose(lambda p: [100 * p["bone"]] * 2)))])
    # Smiling eyes are a dark eye with its lower half painted over, which leaves an arch.
    layer("Happy Eyes", 4, ks(o=pose(lambda p: 100 * p["happy"])), parent=face, shapes=[
        group("Cover", [el([47, 54.5], [12, 10]), el([73, 54.5], [12, 10])], GOLD),
        group("Arch", [el([47, 51], [11, 10]), el([73, 51], [11, 10])], DARK),
    ])
    # Sleeping eyes are the other half: the top painted over, which leaves a closed-lid curve.
    layer("Sleep Eyes", 4, ks(o=pose(lambda p: 100 * p["sleep"])), parent=face, shapes=[
        group("Cover", [el([47, 49], [13, 9]), el([73, 49], [13, 9])], GOLD),
        group("Lid", [el([47, 52], [11, 9]), el([73, 52], [11, 9])], DARK),
    ])
    blink = [(0, [100, 100, 100])]
    for t in blinks:
        blink += [(t, [100, 100, 100]), (t + 4, [100, 8, 100]), (t + 8, [100, 100, 100])]
    lid = tr(p=static([60, 51]), a=static([60, 51]), s=pose(lambda p: [100, 100 * p["lid"]]))
    layer("Eyes", 4, ks(p=static([60, 51, 0]), a=static([60, 51, 0]), s=anim(blink + [(OP, [100, 100, 100])]),
                        o=pose(lambda p: 100 * (1 - p["happy"]))), parent=face, shapes=[
        group("Highlights", [el([48.5, 48.5], [3, 3]), el([74.5, 48.5], [3, 3])], WHITE, lid),
        group("Eyes", [el([47, 51], [9, 11]), el([73, 51], [9, 11])], DARK, lid),
    ])
    layer("Nose", 4, ks(p=static([60, 63, 0]), a=static([60, 63, 0]),
                        s=osc([(a, b, 11, 9) for a, b in sniffs], lambda v: [111 + v, 111 + v, 100])),
          parent=face, shapes=[group("Nose", [el([60, 63], [13, 9])], DARK)])
    layer("Tongue", 4, ks(p=static([60, 74, 0]), a=static([60, 74, 0]),
                          s=anim([(t, [100, 125 if (t // 18) % 2 else 100, 100]) for t in range(0, OP + 1, 18)])),
          parent=face, shapes=[group("Tongue", [el([60, 79], [9, 11])], PINK, tr(
              p=pose(lambda p: [60 + p["tonguex"], 74]), a=static([60, 74]),
              s=pose(lambda p: [100 * (1 + p["tongue"])] * 2)))])
    layer("Mouth", 4, ks(), parent=face, shapes=[group("Mouth", [el([60, 79], [15, 14])], MOUTH, tr(
        p=static([60, 74]), a=static([60, 74]), s=pose(lambda p: [100 * p["mouth"]] * 2)))])
    layer("Muzzle", 4, ks(), parent=face, shapes=[group("Muzzle", [el([60, 70], [34, 26])], CREAM)])
    layer("Head Shape", 4, ks(), parent=head, shapes=[group("Head", [el([60, 56], [64, 58])], GOLD)])
    # Ears hang from the top of the head: they lag behind a turn or a tilt and flop out on a sniff.
    ears = layer("Ear Bounce", 3, ks(p=osc(ear_bounce, lambda v: [0, v, 0])), parent=head)

    def ear(side):
        return pose(lambda p: side * (12 + p["earflap"] + 7 * p["down"]) - 5 * p["look"] - 8 * p["tilt"])

    layer("Ears", 4, ks(p=pose(lambda p: [-2 * p["look"], 0, 0])), parent=ears, shapes=[
        group("Left Ear", [el([0, 0], [18, 38])], EAR, tr(p=static([34, 36]), a=static([0, -17]), r=ear(1))),
        group("Right Ear", [el([0, 0], [18, 38])], EAR, tr(p=static([86, 36]), a=static([0, -17]), r=ear(-1))),
    ])

    doc = {"v": "5.7.4", "fr": 60, "ip": 0, "op": OP, "w": 120, "h": 120, "nm": f"Hyper Pet: {name}",
           "ddd": 0, "assets": [], "layers": layers}
    out = Path(__file__).resolve().parent / "clips" / f"{name}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(doc, indent=1), encoding="utf-8", newline="\n")
    print(f"wrote {out}")


# Every clip starts and ends on the same idle pose, so one can follow another without a jump.

build("look_around", [
    P(0), P(240), P(262, look=-1), P(340, look=-1), P(372, look=1), P(450, look=1), P(472),
    P(690), P(708, down=1), P(760, down=1), P(778), P(OP),
], blinks=(120, 560, 840))

build("sniff_tilt", [
    P(0), P(210), P(230, down=1), P(320, down=1), P(340),
    P(540), P(558, tilt=1), P(640, tilt=1), P(658), P(OP),
], blinks=(130, 440, 760), sniffs=[(240, 320)])

SHUT = dict(lid=0, sleep=1)
build("sleepy", [
    P(0), P(150), P(230, lid=.5), P(270, lid=.5), P(290, lid=.9), P(330, lid=.4),      # eyes droop
    P(344, lid=0), P(350, **SHUT, down=-.3), P(372, **SHUT, mouth=1, down=-.8),         # yawn
    P(430, **SHUT, mouth=1, down=-.8), P(449, **SHUT), P(455, lid=.3), P(480, lid=.3),
    P(506, lid=0), P(520, **SHUT, down=1), P(790, **SHUT, down=1),                      # nods off
    P(798, down=-.5), P(820), P(OP),                                                   # jolts awake
], blinks=(90, 860), shake=[(798, 830, 4, 6)], nod=[(535, 785, 1.2, 45)], zs=(540, 660))

HAPPY = dict(happy=1, tongue=.5, earflap=6)
build("happy", [
    P(0), P(240), P(250, **HAPPY), P(330, **HAPPY), P(345, **HAPPY, tilt=.6), P(420, **HAPPY, tilt=.6),
    P(435, **HAPPY), P(540, **HAPPY), P(552), P(OP),
], blinks=(130, 700, 840), nod=[(250, 540, 1.5, 8)], ear_bounce=[(250, 540, 1.5, 8)])

build("shake", [
    P(0), P(300), P(312, lid=.15, earflap=12), P(400, lid=.15, earflap=12), P(414),
    P(600), P(618, tilt=1), P(680, tilt=1), P(698), P(OP),
], blinks=(150, 520, 800), shake=[(312, 402, 10, 5)])

build("butterfly", [
    P(0), P(240), P(260, look=-1, down=-.6), P(350, look=-.3, down=-.8), P(440, look=.4, down=-.6),
    P(530, look=1, down=-.8), P(545, look=1, down=-1, mouth=1), P(556, look=1, down=-.3),   # snap!
    P(610, look=.5, down=-.6), P(640, tilt=1), P(730, tilt=1), P(750), P(OP),               # ...huh?
], blinks=(130, 800), butterfly=([
    (0, 28, 30, 0), (240, 28, 30, 0), (260, 30, 23, 100), (350, 46, 19, 100), (440, 64, 23, 100),
    (530, 82, 19, 100), (555, 86, 20, 100), (610, 92, 18, 0), (OP, 92, 18, 0),
], (240, 612, 37.5, 5)))

CHEW = dict(bone=1, tongue=-1, down=.3)
build("bone", [
    P(0), P(240), P(255, **{**CHEW, "bone": 1.15}), P(265, **CHEW), P(480, **CHEW),
    P(490, tongue=-1, down=-.5, lid=.1), P(515, tongue=-1),                                 # gulp
    P(530, happy=1, tongue=.3, tonguex=-6), P(560, happy=1, tongue=.3, tonguex=6),          # licks lips
    P(590, happy=1, tongue=.3, tonguex=-6), P(620, happy=1, tongue=.3), P(680, happy=1), P(692), P(OP),
], blinks=(130, 790), nod=[(280, 472, 2.5, 16)])
