"""Абстрактная аргументация (Дунг) с grounded-семантикой и предпочтениями.

Аргумент — вершина графа; атака — ребро. Предпочтения превращают атаки в поражения
(defeat): атака A→B успешна, если сила A не меньше силы B. Grounded-разметка
(наименьшая неподвижная точка) детерминирована и полиномиальна:
    IN    — все поразившие аргументы OUT;
    OUT   — есть поразивший аргумент IN;
    UNDEC — остальное (взаимные поражения равной силы = спор).
Это логическая разметка, не числовой «скор».
"""
from __future__ import annotations

from dataclasses import dataclass, field

STRENGTH = {"auth": 2, "claim": 1}


@dataclass
class Argument:
    id: str
    conclusion: str          # ключ литерала («p(a)» или «not p(a)»)
    stance: str              # pro | con — относительно оцениваемой гипотезы
    strength: str            # auth | claim
    basis: tuple = ()        # записи (F:/C:/EV:), на которых держится аргумент
    origin: str = ""         # aid вывода или EV:-аргумент человека
    label: str = "UNDEC"


@dataclass
class Framework:
    arguments: dict = field(default_factory=dict)
    attacks: set = field(default_factory=set)   # (attacker, target)

    def add(self, arg: Argument) -> None:
        self.arguments[arg.id] = arg

    def attack(self, a: str, b: str) -> None:
        if a != b:
            self.attacks.add((a, b))

    def defeats(self) -> set:
        out = set()
        for a, b in self.attacks:
            if STRENGTH[self.arguments[a].strength] >= STRENGTH[self.arguments[b].strength]:
                out.add((a, b))
        return out

    def grounded(self) -> dict:
        defeats = self.defeats()
        attackers: dict = {k: set() for k in self.arguments}
        for a, b in defeats:
            attackers[b].add(a)
        label = {k: "UNDEC" for k in self.arguments}
        changed = True
        while changed:
            changed = False
            for k in sorted(self.arguments):
                if label[k] != "UNDEC":
                    continue
                if all(label[x] == "OUT" for x in attackers[k]):
                    label[k] = "IN"
                    changed = True
                elif any(label[x] == "IN" for x in attackers[k]):
                    label[k] = "OUT"
                    changed = True
        for k, v in label.items():
            self.arguments[k].label = v
        return label
