"""Every write is one `change` event (wiki/Property-Graph.md, the learning
slice 2026-11-20). The demo builders describe their graphs in the words of
what happens — a source added, a topic accepted, an item accepted — and
this turns each into the ops of a `change`, as src/loretile/ops.cljc does
in the app. A node's `is` names its class; absent = an ordinary node."""


def _rest(m, *drop):
    return {k: v for k, v in m.items() if k not in ("id",) + drop}


def change_of(type_, kv):
    """The ops of the `change` for one described step."""
    if type_ == "source-added":
        s = kv["source"]
        return [[":node-set", s["id"], {**_rest(s), "is": ":source"}]]
    if type_ == "concept-accepted":
        c = kv["concept"]
        return [[":node-set", c["id"], {**_rest(c), "status": ":accepted"}]]
    if type_ == "edge-accepted":
        e = kv["edge"]
        return [[":edge-set", e["from"], e["type"], e["to"], _rest(e, "from", "to", "type")]]
    if type_ == "object-added":
        o = kv["object"]
        return [[":node-set", o["id"], {**_rest(o), "is": ":object"}]]
    if type_ == "item-added":
        i = kv["item"]
        return [[":node-set", i["id"], {**_rest(i), "is": ":item"}]]
    if type_ == "item-accepted":
        return [[":node-set", kv["id"], {"status": ":active"}]]
    if type_ == "question-raised":
        return [[":node-set", kv["id"], {"is": ":question", "concept-id": kv["concept-id"], "question": kv["question"]}]]
    if type_ == "course-published":
        c = kv["course"]
        return [[":node-set", c["id"], {**_rest(c), "is": ":course"}]]
    raise ValueError(f"no change for {type_!r}")


def as_change(e, type_, kv):
    """Turn the event dict `e` (ids, actor, clock already set) into the
    `change` for `type_` with `kv`."""
    e["ev/type"] = ":change"
    e["ops"] = change_of(type_, kv)
    return e
