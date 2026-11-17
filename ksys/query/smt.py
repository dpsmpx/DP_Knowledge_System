"""SMT-бэкенд для числовых ограничений (необязательный: z3-solver).

Числовое знание в базе:
    value(s, атрибут, N)                   — x(s, атрибут) = N
    bound(s, атрибут, "op", N)             — x(s, атрибут) op N   (op ∈ < <= > >= = !=)
    attr_cmp(s1, a1, "op", s2, a2)         — x(s1, a1) op x(s2, a2)
Проверки:
    consistency()  — выполнима ли система; при невыполнимости — unsat core → записи и provenance;
    entails(...)   — PROVEN / DISPROVEN / UNKNOWN для числового утверждения.
Без z3: детерминированное распространение интервальных границ для value/bound
(одна переменная); при наличии attr_cmp — UNKNOWN с ошибкой SMT_UNAVAILABLE.
Учитываются только утверждения без ограничения по времени (или истинные на дату ``at``).
"""
from __future__ import annotations

from decimal import Decimal

from ..core.errors import ErrorKind
from ..ir.terms import NUM, STR

OPS = ("<", "<=", ">", ">=", "=", "!=")


def available() -> bool:
    try:
        import z3  # noqa: F401
        return True
    except ImportError:
        return False


def numeric_constraints(engine, at: str | None = None) -> list:
    """[(aid, kind, payload)] — числовые утверждения авторитетного слоя."""
    out = []
    for aid, a in sorted(engine.items.items()):
        if a.negated:
            continue
        if at is None and not a.interval.unbounded:
            continue
        if at is not None and not a.interval.contains(at):
            continue
        args = a.atom.args
        p = a.atom.predicate
        if p == "value" and len(args) == 3 and args[2].kind == NUM:
            out.append((aid, "eq", ((args[0].value, args[1].value), "=", args[2].number)))
        elif p == "bound" and len(args) == 4 and args[2].kind == STR and args[2].value in OPS and args[3].kind == NUM:
            out.append((aid, "bound", ((args[0].value, args[1].value), args[2].value, args[3].number)))
        elif p == "attr_cmp" and len(args) == 5 and args[2].kind == STR and args[2].value in OPS:
            out.append((aid, "cmp", ((args[0].value, args[1].value), args[2].value, (args[3].value, args[4].value))))
    return out


def _z3_expr(z3, x, op, y):
    return {"<": x < y, "<=": x <= y, ">": x > y, ">=": x >= y, "=": x == y, "!=": x != y}[op]


def _solver(cons):
    import z3
    s = z3.Solver()
    vars_ = {}

    def v(key):
        if key not in vars_:
            vars_[key] = z3.Real(f"{key[0]}.{key[1]}")
        return vars_[key]
    for aid, kind, (lhs, op, rhs) in cons:
        y = v(rhs) if kind == "cmp" else z3.RealVal(str(rhs))
        s.assert_and_track(_z3_expr(z3, v(lhs), op, y), aid)
    return s, v


def consistency(engine, at: str | None = None) -> dict:
    cons = numeric_constraints(engine, at)
    if not cons:
        return {"status": "SAT", "constraints": 0, "core": []}
    if available():
        import z3
        s, _ = _solver(cons)
        r = s.check()
        if r == z3.unsat:
            return {"status": "UNSAT", "constraints": len(cons), "core": sorted(str(c) for c in s.unsat_core()),
                    "backend": "z3"}
        return {"status": "SAT" if r == z3.sat else "UNKNOWN", "constraints": len(cons), "core": [], "backend": "z3"}
    if any(k == "cmp" for _, k, _ in cons):
        return {"status": "UNKNOWN", "constraints": len(cons), "core": [],
                "error": ErrorKind.SMT_UNAVAILABLE.value, "backend": "intervals"}
    return _interval_check(cons)


def _interval_check(cons) -> dict:
    """Без SMT: границы на каждую переменную; конфликт — пустой допустимый интервал."""
    lo: dict = {}
    hi: dict = {}
    eq: dict = {}
    for aid, kind, (key, op, n) in cons:
        n = Decimal(n)
        if op == "=":
            eq.setdefault(key, []).append((n, aid))
        if op in (">", ">="):
            cur = lo.get(key)
            if cur is None or n > cur[0] or (n == cur[0] and op == ">"):
                lo[key] = (n, op == ">", aid)
        if op in ("<", "<="):
            cur = hi.get(key)
            if cur is None or n < cur[0] or (n == cur[0] and op == "<"):
                hi[key] = (n, op == "<", aid)
    for key in sorted(set(lo) | set(hi) | set(eq)):
        vals = eq.get(key, [])
        if len({v for v, _ in vals}) > 1:
            return {"status": "UNSAT", "constraints": len(cons), "core": sorted(a for _, a in vals), "backend": "intervals"}
        if key in lo and key in hi:
            (l, ls, la), (h, hs, ha) = lo[key], hi[key]
            if l > h or (l == h and (ls or hs)):
                return {"status": "UNSAT", "constraints": len(cons), "core": sorted({la, ha}), "backend": "intervals"}
        for v, va in vals:
            if key in lo and (v < lo[key][0] or (v == lo[key][0] and lo[key][1])):
                return {"status": "UNSAT", "constraints": len(cons), "core": sorted({va, lo[key][2]}), "backend": "intervals"}
            if key in hi and (v > hi[key][0] or (v == hi[key][0] and hi[key][1])):
                return {"status": "UNSAT", "constraints": len(cons), "core": sorted({va, hi[key][2]}), "backend": "intervals"}
    return {"status": "SAT", "constraints": len(cons), "core": [], "backend": "intervals"}


def entails(engine, subject: str, attribute: str, op: str, n, at: str | None = None) -> dict:
    """Следует ли x(subject, attribute) op n из числового знания базы."""
    cons = numeric_constraints(engine, at)
    if not available():
        return {"status": "UNKNOWN", "error": ErrorKind.SMT_UNAVAILABLE.value}
    import z3
    s, v = _solver(cons)
    if s.check() == z3.unsat:
        return {"status": "BOTH", "note": "числовое знание противоречиво", "core": sorted(str(c) for c in s.unsat_core())}
    x = v((subject, attribute))
    goal = _z3_expr(z3, x, op, z3.RealVal(str(n)))
    s.push()
    s.add(z3.Not(goal))
    neg_unsat = s.check() == z3.unsat
    s.pop()
    s.push()
    s.add(goal)
    pos_unsat = s.check() == z3.unsat
    s.pop()
    if neg_unsat:
        return {"status": "PROVEN", "backend": "z3"}
    if pos_unsat:
        return {"status": "DISPROVEN", "backend": "z3"}
    return {"status": "UNKNOWN", "backend": "z3"}
