"""LF → IR: нормализация именных групп, разрешение ссылок, построение узлов.

Семантический построитель:
* не угадывает: местоимение без решения человека → UNRESOLVED_REFERENCE + вопрос;
* использует сохранённые решения (привязки ссылок, алиасы ключей) через Resolver;
* для каждого прочтения выдаёт Interpretation с узлами IR, пересказом и метками.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from ..core.enums import KnowledgeType, Modality
from ..core.errors import Diagnostic, ErrorKind, Severity
from ..core.ids import canonical_json, digest, make_id
from ..ir.ksf import Directive, expand_directive
from ..ir.nodes import Assertion, Claim, Decision, Definition, Fact, Hypothesis, Node, Question, Rule
from ..ir.terms import ALWAYS, Atom, Const, Interval, Literal, Var, date, num, string, sym
from ..parser.lf import LF, NP, Clause, ParseOutcome
from .normalize import ME_KEY, key_from_words

PREFERENCE_PREDICATES = {"likes", "prefers", "dislikes"}


class Resolver(Protocol):
    def binding(self, origin: str, slot: str) -> str | None: ...
    def canonical(self, key: str) -> str: ...
    def label(self, key: str) -> str | None: ...


class NullResolver:
    def binding(self, origin: str, slot: str) -> str | None:
        return None

    def canonical(self, key: str) -> str:
        return key

    def label(self, key: str) -> str | None:
        return None


@dataclass
class Interpretation:
    reading_id: str
    labels: tuple
    nodes: list
    paraphrase: str
    unresolved: list = field(default_factory=list)   # [{slot, text, question}]
    diagnostics: list = field(default_factory=list)
    form: str = ""
    knowledge_type: str | None = None
    surface: dict = field(default_factory=dict)      # ключ → поверхностный текст (для ярлыков)
    notes: list = field(default_factory=list)        # неформализованные остатки (текст причин решения…)

    @property
    def complete(self) -> bool:
        return not self.unresolved and bool(self.nodes)

    def candidate_dict(self, origin: str, input_hash: str, n_readings: int) -> dict:
        node_ids = [n.id for n in self.nodes]
        return {"id": make_id("CAND", {"origin": origin, "reading": self.reading_id, "nodes": node_ids}),
                "reading_id": self.reading_id, "nodes": [n.to_dict() for n in self.nodes],
                "paraphrase": self.paraphrase,
                "diagnostics": [d.to_dict() for d in self.diagnostics],
                "ambiguity": {"readings": n_readings, "labels": list(self.labels),
                              "unresolved": self.unresolved, "notes": self.notes},
                "input_hash": input_hash, "form": self.form, "knowledge_type": self.knowledge_type,
                "surface": self.surface}


class _Unresolved(Exception):
    pass


class SemanticBuilder:
    def __init__(self, resolver: Resolver | None = None, mode: str = "assert"):
        """mode: 'assert' — утверждения человека (кандидаты-факты); 'import' — тексты источника (заявления)."""
        self.resolver = resolver or NullResolver()
        self.mode = mode

    # -------------------------------------------------------------- public
    def build_all(self, outcome: ParseOutcome, origin: str) -> list[Interpretation]:
        from .paraphrase import paraphrase_interpretation
        out = []
        for lf in outcome.readings:
            interp = self.build(lf, origin)
            interp.paraphrase = paraphrase_interpretation(interp, lf, self.resolver)
            out.append(interp)
        return out

    def build(self, lf: LF, origin: str) -> Interpretation:
        rid = "RD:" + digest(lf.key_repr())
        interp = Interpretation(rid, lf.reading, [], "", form=lf.form, knowledge_type=lf.knowledge_type)
        self._origin = origin
        self._interp = interp
        try:
            if lf.form in ("assertion", "question"):
                self._assertions(lf, interp)
            elif lf.form == "rule":
                self._rules(lf, interp)
            elif lf.form == "directive":
                name, pred = lf.directive
                interp.nodes.extend(expand_directive(Directive(name, (pred,))))
            elif lf.form == "definition":
                self._definition(lf, interp)
            elif lf.form == "decision":
                self._decision(lf, interp)
        except _Unresolved:
            interp.nodes = []
        return interp

    # -------------------------------------------------------------- terms
    def term(self, np: NP, slot: str):
        if np.kind == "name":
            key = self.resolver.canonical(key_from_words(np.words))
            if np.slot == "subj" or key not in self._interp.surface:  # форма подлежащего — ближе к именительному
                self._interp.surface[key] = np.text
            return sym(key)
        if np.kind == "me":
            return sym(ME_KEY)
        if np.kind == "number":
            return num(np.value)
        if np.kind == "date":
            return date(np.value)
        if np.kind == "string":
            return string(np.value)
        if np.kind in ("var", "wh"):
            return Var(np.value)
        if np.kind == "group":
            members = [self.term(m, f"{slot}.m{k}") for k, m in enumerate(np.members)]
            if any(m is None for m in members):
                return None
            keys = sorted(m.key().strip('"') if not isinstance(m, Var) else m.name for m in members)
            key = "grp_" + "__".join(k for k in keys if k)
            self._interp.surface.setdefault(key, np.text)
            return sym(key)
        if np.kind == "pronoun":
            bound = self.resolver.binding(self._origin, slot)
            if bound:
                return sym(bound)
            question = ("Чей атрибут имеется в виду?" if np.text == "∅"
                        else f"Кто или что обозначено словом «{np.text}»?")
            self._interp.unresolved.append({"slot": slot, "text": np.text, "question": question})
            self._interp.diagnostics.append(Diagnostic(ErrorKind.UNRESOLVED_REFERENCE, question,
                                                       Severity.WARNING, slot))
            return None
        raise ValueError(np.kind)

    def _atom(self, clause: Clause, slot_prefix: str) -> Atom | None:
        args = []
        missing = False
        for ai, np in enumerate(clause.args):
            t = self.term(np, f"{slot_prefix}.{ai}")
            if t is None:
                missing = True
            args.append(t)
        if missing:
            return None
        return Atom(clause.predicate, tuple(args))

    def _interval(self, lf: LF) -> Interval:
        if not lf.interval:
            return ALWAYS
        return Interval(lf.interval[0], lf.interval[1])

    def _context(self, lf: LF) -> str | None:
        if lf.context is None:
            return None
        t = self.term(lf.context, "context")
        return t.value if isinstance(t, Const) else None

    # -------------------------------------------------------------- forms
    def _knowledge_type(self, lf: LF, atoms) -> KnowledgeType:
        if lf.knowledge_type:
            return KnowledgeType(lf.knowledge_type)
        if lf.hypothesis:
            return KnowledgeType.HYPOTHESIS
        if lf.author is not None:
            return KnowledgeType.EXTERNAL_CLAIM
        if any(a.predicate in PREFERENCE_PREDICATES and a.args[0] == sym(ME_KEY) for a in atoms):
            return KnowledgeType.PERSONAL_PREFERENCE
        if any(a.args and a.args[0] == sym(ME_KEY) for a in atoms):
            return KnowledgeType.PERSONAL_OBSERVATION
        return KnowledgeType.OBJECTIVE_FACT

    def _assertions(self, lf: LF, interp: Interpretation) -> None:
        atoms = []
        for ci, clause in enumerate(lf.clauses):
            atom = self._atom(clause, f"c{ci}")
            atoms.append((atom, clause.negated))
        author = None
        if lf.author is not None:
            t = self.term(lf.author, "author")
            author = t.value if isinstance(t, Const) else None
        context = self._context(lf)
        if interp.unresolved:
            raise _Unresolved()
        kt = self._knowledge_type(lf, [a for a, _ in atoms])
        interp.knowledge_type = kt.value
        if lf.form == "question":
            interp.nodes.append(Question(tuple(Literal(a, neg) for a, neg in atoms)))
            return
        modality = Modality(lf.modality)
        interval = self._interval(lf)
        for atom, neg in atoms:
            if not atom.is_ground():
                continue
            a = Assertion(atom, neg, modality, context, interval)
            if lf.hypothesis:
                interp.nodes.append(Hypothesis(a))
            elif author is not None or self.mode == "import":
                interp.nodes.append(Claim(a, author, kt))
            else:
                interp.nodes.append(Fact(a, kt))
        for clause in lf.clauses:  # коллективное прочтение — явные члены группы
            for np in clause.args:
                if np.kind == "group":
                    g = self.term(np, "group")
                    for m in np.members:
                        mem = Assertion(Atom("member_of", (self.term(m, "member"), g)))
                        interp.nodes.append(Claim(mem, author, kt) if (author or self.mode == "import") else Fact(mem, kt))

    def _rules(self, lf: LF, interp: Interpretation) -> None:
        body = []
        for ci, clause in enumerate(lf.conditions):
            atom = self._atom(clause, f"b{ci}")
            body.append(Literal(atom, clause.negated) if atom is not None else None)
        heads = []
        for ci, clause in enumerate(lf.clauses):
            atom = self._atom(clause, f"h{ci}")
            heads.append(Literal(atom, clause.negated) if atom is not None else None)
        if interp.unresolved:
            raise _Unresolved()
        body_t = tuple(body)
        bound = set().union(*(b.variables() for b in body_t)) if body_t else set()
        for h in heads:
            free = h.variables() - bound
            if free:
                interp.diagnostics.append(Diagnostic(ErrorKind.LOGIC_ERROR,
                                                     f"переменные {sorted(free)} есть только в заключении"))
                interp.nodes = []
                return
            if lf.hypothesis or lf.author is not None or self.mode == "import" or lf.modality != "asserted":
                interp.notes.append("правило из источника/гипотетическое: принимается только явным решением человека")
            interp.nodes.append(Rule(h, body_t))

    def _definition(self, lf: LF, interp: Interpretation) -> None:
        term_np, genus_np, definiens = lf.definition
        term = self.term(term_np, "term")
        genus = self.term(genus_np, "genus") if genus_np is not None else None
        interp.nodes.append(Definition(term.value, genus.value if genus is not None else None, definiens))
        if genus is not None:
            a = Assertion(Atom("is_a", (term, genus)), False, Modality(lf.modality), self._context(lf),
                          self._interval(lf))
            interp.nodes.append(Claim(a, None, KnowledgeType.OBJECTIVE_FACT) if self.mode == "import" else Fact(a))
        interp.notes.append("определяющая часть сохранена текстом (частичная формализация)")

    def _decision(self, lf: LF, interp: Interpretation) -> None:
        action, because, effect = lf.decision
        refs: tuple = ()
        if isinstance(because, tuple):
            refs = because
        elif isinstance(because, LF):
            sub = SemanticBuilder(self.resolver, "assert").build(because, self._origin + "#because")
            refs = tuple(n.id for n in sub.nodes if isinstance(n, Fact))
            if sub.unresolved or not refs:
                interp.notes.append(f"основание не формализовано: {because.key_repr()}")
        elif isinstance(because, str):
            interp.notes.append(f"основание (текст): {because}")
        interp.nodes.append(Decision(action, refs, effect))
        interp.knowledge_type = KnowledgeType.PERSONAL_DECISION.value


def input_hash(text: str) -> str:
    return digest(canonical_json(" ".join(text.split())))
