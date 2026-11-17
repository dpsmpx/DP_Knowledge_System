% Базовая библиотека правил. НЕ загружается автоматически: это знание, и принять его может только
% человек — командой `knowledge rules load rules/core.ks` (каждое правило попадает в журнал с provenance).
#transitive part_of.
#transitive located_in.
#inverse part_of, has_part.
% вложенность классов: экземпляр подкласса — экземпляр надкласса
rule subclass_chain: is_a(X, Z) :- is_a(X, Y), is_a(Y, Z).
% зависимость через использование
rule uses_depends: depends_on(X, Y) :- uses(X, Y), requires(Y, Z), not available(Z).
