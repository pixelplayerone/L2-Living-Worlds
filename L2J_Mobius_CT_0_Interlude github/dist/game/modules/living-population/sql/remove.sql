-- Living Population module opt-in cleanup script.
-- The platform NEVER runs this on its own, because the table holds bot progress. Run it by hand only when you
-- intend to permanently discard the living population.
DROP TABLE IF EXISTS living_population_bots;
DROP TABLE IF EXISTS living_town_stock;
