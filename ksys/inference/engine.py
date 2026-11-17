"""Полунаивный Datalog с аннотациями, обоснованиями и инкрементальным TMS (DRed).

Модель:
* элемент (item) — ground-утверждение (Assertion) с ID ``A:…``; литерал ``¬p`` —
  отдельный ключ (p, negated=True): сильное отрицание, NAF нет → программа монотонна;
* аннотации при выводе: контекст — встреча (None = глобальный; разные → несовместимы),
  интервал — пересечение (пустое → нет вывода), модальность — самая слабая;
* каждое срабатывание правила записывается как обоснование
  ``DRV:hash(заключение, правило, посылки)`` — даже если заключение уже было;
  поэтому множество обоснований полно, и DRed корректен;
* базовые элементы (asserted) хранят множество источников (F:/C:-записей) и никогда
  не удаляются выводом.

Инкрементальность:
    add_base / add_rule     — полунаивное распространение только от новых элементов;
    remove_base / remove_rule — DRed: переудаление зависимых → повторный вывод по
                                оставшимся обоснованиям → окончательное удаление.
Каждая операция возвращает Delta (добавлено/удалено/выведено заново) — для объяснения
«какие выводы затронуты».
"""
from __future__ import annotations

from dataclasses import dataclass, field

from ..core.enums import MODALITY_RANK, Modality
from ..core.errors import ErrorKind, KSError
from ..ir.nodes import Assertion, Derivation, Rule
from ..ir.terms import ALWAYS, NUM, Comparison, Const, Interval, Literal, Var

_BOTTOM = object()


def meet_context(a: str | None, b: str | None):
    if a is None:
        return b
    if b is None or a == b:
        return a
    return _BOTTOM


def weakest(mods) -> Modality:
    return min(mods, key=lambda m: MODALITY_RANK[m])


def _term_eq(a: Const, b: Const) -> bool:
    if a.kind == NUM and b.kind == NUM:
        return a.number == b.number
    return a == b


def unify(pattern: Literal, a: Assertion, binding: dict) -> dict | None:
    if pattern.negated != a.negated or pattern.atom.predicate != a.atom.predicate \
            or len(pattern.atom.args) != len(a.atom.args):
        return None
    b = binding
    copied = False
    for p, v in zip(pattern.atom.args, a.atom.args):
        if isinstance(p, Var):
            bound = b.get(p.name)
            if bound is None:
                if not copied:
                    b = dict(b)
                    copied = True
                b[p.name] = v
            elif not _term_eq(bound, v):
                return None
        elif not _term_eq(p, v):
            return None
    return b


def complement_support(engine: "Engine", a: Assertion) -> list:
    """Элементы с противоположной полярностью, совместимым контекстом и пересекающимся интервалом."""
    out = []
    for aid, _ in engine.match(Literal(a.atom, not a.negated)):
        b = engine.items[aid]
        if meet_context(a.context, b.context) is _BOTTOM:
            continue
        if a.interval.intersect(b.interval) is None:
            continue
        out.append(aid)
    return out


@dataclass(frozen=True)
class Justification:
    id: str
    conclusion: str
    rule: str
    premises: tuple


@dataclass
class Delta:
    added: set = field(default_factory=set)
    removed: set = field(default_factory=set)
    rederived: set = field(default_factory=set)

    def merge(self, other: "Delta") -> "Delta":
        self.added |= other.added
        self.removed |= other.removed
        self.rederived |= other.rederived
        self.added -= self.removed
        return self


class Engine:
    def __init__(self, max_items: int = 2_000_000):
        self.items: dict[str, Assertion] = {}
        self.base: dict[str, set] = {}
        self.just: dict[str, Justification] = {}
        self.by_concl: dict[str, set] = {}
        self.by_prem: dict[str, set] = {}
        self.rules: dict[str, Rule] = {}
        self.index: dict[tuple, set] = {}
        self.index_a0: dict[tuple, set] = {}
        self.index_a1: dict[tuple, set] = {}
        self.max_items = max_items

    @classmethod
    def from_items(cls, items: dict) -> "Engine":
        """Движок только для сопоставления (без правил) — проверки схемы/ограничений над набором утверждений."""
        e = cls()
        for aid, a in items.items():
            e._add_item(aid, a)
        return e

    # ------------------------------------------------------------------ index
    @staticmethod
    def _key(a: Assertion) -> tuple:
        return (a.atom.predicate, a.negated)

    def _add_item(self, aid: str, a: Assertion) -> None:
        self.items[aid] = a
        self.index.setdefault(self._key(a), set()).add(aid)
        self.index_a0.setdefault(self._key(a) + (a.atom.args[0],), set()).add(aid)
        if len(a.atom.args) > 1:
            self.index_a1.setdefault(self._key(a) + (a.atom.args[1],), set()).add(aid)
        if len(self.items) > self.max_items:
            raise KSError(ErrorKind.LOGIC_ERROR, f"inference exceeded {self.max_items} items (runaway rules?)")

    def _drop_item(self, aid: str) -> None:
        a = self.items.pop(aid)
        self.index[self._key(a)].discard(aid)
        self.index_a0[self._key(a) + (a.atom.args[0],)].discard(aid)
        if len(a.atom.args) > 1:
            self.index_a1[self._key(a) + (a.atom.args[1],)].discard(aid)

    def candidates(self, lit: Literal, binding: dict):
        """Кандидаты по самому узкому индексу (предикат / 1-й / 2-й аргумент).
        Порядок обхода не влияет на результат: неподвижная точка — множество с контентными ID."""
        key = (lit.atom.predicate, lit.negated)
        best = self.index.get(key, ())
        args = lit.atom.args
        for pos, idx in ((0, self.index_a0), (1, self.index_a1)):
            if pos >= len(args):
                continue
            t = args[pos]
            if isinstance(t, Var):
                t = binding.get(t.name)
            if t is not None and t.kind != NUM:
                pool = idx.get(key + (t,), ())
                if len(pool) < len(best):
                    best = pool
        return tuple(best)

    # ------------------------------------------------------------------ evaluation
    def _join(self, lits: list, skip: int, binding: dict, chosen: dict, pos: int = 0):
        if pos == len(lits):
            yield binding, chosen
            return
        if pos == skip:
            yield from self._join(lits, skip, binding, chosen, pos + 1)
            return
        lit = lits[pos]
        for aid in self.candidates(lit, binding):
            b = unify(lit, self.items[aid], binding)
            if b is not None:
                yield from self._join(lits, skip, b, {**chosen, pos: aid}, pos + 1)

    def _fire(self, rid: str, rule: Rule, binding: dict, chosen: dict, out: set) -> None:
        for c in rule.comparisons():
            if not c.evaluate(binding):
                return
        premises = tuple(chosen[i] for i in range(len(chosen)))
        prem_a = [self.items[p] for p in premises]
        ctx = None
        for a in prem_a:
            ctx = meet_context(ctx, a.context)
            if ctx is _BOTTOM:
                return
        iv = ALWAYS
        for a in prem_a:
            iv = iv.intersect(a.interval)
            if iv is None:
                return
        head_atom = rule.head.atom.substitute(binding)
        if not head_atom.is_ground():
            raise KSError(ErrorKind.LOGIC_ERROR, f"unsafe rule {rid}: non-ground head {head_atom.key()}")
        concl = Assertion(head_atom, rule.head.negated, weakest([a.modality for a in prem_a]), ctx, iv)
        aid = concl.id
        jid = Derivation(aid, rid, premises).id
        if jid not in self.just:
            self.just[jid] = Justification(jid, aid, rid, premises)
            self.by_concl.setdefault(aid, set()).add(jid)
            for p in premises:
                self.by_prem.setdefault(p, set()).add(jid)
        if aid not in self.items:
            self._add_item(aid, concl)
            out.add(aid)

    def _propagate(self, delta: set) -> set:
        """Полунаивная неподвижная точка от новых элементов ``delta``. Возвращает все выведенные новые."""
        produced: set = set()
        rules = sorted(self.rules.items())
        while delta:
            by_key: dict = {}
            for aid in delta:
                by_key.setdefault(self._key(self.items[aid]), []).append(aid)
            new: set = set()
            for rid, rule in rules:
                lits = rule.positive_atoms()
                for i, lit in enumerate(lits):
                    for aid in by_key.get((lit.atom.predicate, lit.negated), ()):
                        b = unify(lit, self.items[aid], {})
                        if b is None:
                            continue
                        for binding, chosen in self._join(lits, i, b, {i: aid}):
                            self._fire(rid, rule, binding, chosen, new)
            produced |= new
            delta = new
        return produced

    def _fire_rule_all(self, rid: str, rule: Rule) -> set:
        new: set = set()
        lits = rule.positive_atoms()
        for binding, chosen in self._join(lits, -1, {}, {}):
            self._fire(rid, rule, binding, chosen, new)
        return new

    def bulk_load(self, rules, facts) -> Delta:
        """Массовая загрузка (открытие базы): все правила и факты, затем ОДНА полунаивная неподвижная точка.
        Результат идентичен последовательным add_rule/add_base (проверяется тестом)."""
        for rid, rule in rules:
            self.rules.setdefault(rid, rule)
        delta: set = set()
        for a, source in facts:
            aid = a.id
            self.base.setdefault(aid, set()).add(source)
            if aid not in self.items:
                self._add_item(aid, a)
                delta.add(aid)
        produced = self._propagate(set(self.items))
        return Delta(added=delta | produced)

    # ------------------------------------------------------------------ updates (incremental)
    def add_base(self, a: Assertion, source: str) -> Delta:
        aid = a.id
        self.base.setdefault(aid, set()).add(source)
        if aid in self.items:
            return Delta()
        self._add_item(aid, a)
        produced = self._propagate({aid})
        return Delta(added={aid} | produced)

    def add_rule(self, rid: str, rule: Rule) -> Delta:
        if rid in self.rules:
            return Delta()
        self.rules[rid] = rule
        new = self._fire_rule_all(rid, rule)
        produced = self._propagate(set(new))
        return Delta(added=new | produced)

    def remove_base(self, aid: str, source: str) -> Delta:
        srcs = self.base.get(aid)
        if not srcs or source not in srcs:
            return Delta()
        srcs.discard(source)
        if srcs:
            return Delta()
        del self.base[aid]
        return self._dred({aid})

    def remove_rule(self, rid: str) -> Delta:
        if rid not in self.rules:
            return Delta()
        del self.rules[rid]
        seeds = set()
        for jid in sorted(j for j, js in self.just.items() if js.rule == rid):
            seeds.add(self.just[jid].conclusion)
            self._drop_just(jid)
        return self._dred(seeds)

    def _drop_just(self, jid: str) -> None:
        js = self.just.pop(jid)
        self.by_concl.get(js.conclusion, set()).discard(jid)
        for p in js.premises:
            self.by_prem.get(p, set()).discard(jid)

    def _dred(self, seeds: set) -> Delta:
        # 1. переудаление: все не-базовые элементы, транзитивно зависящие от seeds
        doomed: set = set()
        stack = [s for s in seeds if s in self.items and s not in self.base]
        while stack:
            x = stack.pop()
            if x in doomed:
                continue
            doomed.add(x)
            for jid in self.by_prem.get(x, ()):
                c = self.just[jid].conclusion
                if c not in doomed and c not in self.base and c in self.items:
                    stack.append(c)
        if not doomed:
            return Delta()
        saved = {x: self.items[x] for x in doomed}
        for x in doomed:
            self._drop_item(x)
        # 2. повторный вывод по оставшимся обоснованиям, посылки которых живы
        restored: set = set()
        changed = True
        while changed:
            changed = False
            for x in sorted(doomed - restored):
                for jid in sorted(self.by_concl.get(x, ())):
                    if all(p in self.items for p in self.just[jid].premises):
                        self._add_item(x, saved[x])
                        restored.add(x)
                        changed = True
                        break
        # 3. окончательное удаление: элементы и обоснования, опиравшиеся на них
        gone = doomed - restored
        for x in gone:
            for jid in list(self.by_concl.get(x, ())) + list(self.by_prem.get(x, ())):
                if jid in self.just:
                    self._drop_just(jid)
            self.by_concl.pop(x, None)
            self.by_prem.pop(x, None)
        return Delta(removed=gone, rederived=restored)

    # ------------------------------------------------------------------ queries
    def match(self, lit: Literal) -> list[tuple[str, dict]]:
        """Все элементы, унифицируемые с литералом (с переменными), в порядке ID."""
        out = []
        for aid in sorted(self.candidates(lit, {})):
            b = unify(lit, self.items[aid], {})
            if b is not None:
                out.append((aid, b))
        return out

    def is_base(self, aid: str) -> bool:
        return aid in self.base

    def justifications(self, aid: str) -> list[Justification]:
        return [self.just[j] for j in sorted(self.by_concl.get(aid, ()))]

    def dependents(self, aid: str, limit: int = 100_000) -> set:
        """Все элементы, в обоснованиях которых (транзитивно) участвует aid."""
        out: set = set()
        stack = [aid]
        while stack and len(out) < limit:
            x = stack.pop()
            for jid in self.by_prem.get(x, ()):
                c = self.just[jid].conclusion
                if c not in out:
                    out.add(c)
                    stack.append(c)
        return out

    def snapshot(self) -> tuple:
        """Каноническое состояние (для сравнения инкрементального и полного пересчёта)."""
        return (tuple(sorted(self.items)), tuple(sorted((k, tuple(sorted(v))) for k, v in self.base.items())),
                tuple(sorted(self.just)))
