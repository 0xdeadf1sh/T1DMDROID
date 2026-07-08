package com.t1dm.data.meals

/**
 * The bundled starter glycemic dictionary (PLAN.private.md Phase 4 deliverable 3 / risk S9). A
 * curated ~200-food subset of common staples, sufficient to build most meals; the user extends it
 * with the food builder. No proprietary database is reproduced wholesale — this is the S9 fallback
 * (a small curated set + custom-curve overrides).
 *
 * ## Provenance & licensing
 * - **Carbohydrate per 100 g** — representative values from **USDA FoodData Central** (SR Legacy /
 *   Foundation Foods). USDA FDC is a work of the U.S. Government and is in the **public domain**
 *   (17 U.S.C. §105). Individual nutrient numbers are, in any case, uncopyrightable **facts**.
 * - **Glycemic index** — representative published values from the **International Tables of
 *   Glycemic Index and Glycemic Load** (Atkinson, Foster-Powell & Brand-Miller, *Diabetes Care*
 *   31:2281–2283, 2008) and its 2002 predecessor (Foster-Powell, Holt & Brand-Miller). GI figures
 *   are measured **data**, not creative expression; they are re-keyed individually and rounded to
 *   representative integers rather than copied as a table's selection/arrangement, so no compilation
 *   or database right is implicated.
 * - GI is a population mean with wide inter-food variance; treat these as in-distribution defaults
 *   for the appearance-curve gamma, not per-person truth. A `null` GI marks an unmeasured/low-carb
 *   food (the resolver falls back to a medium default).
 *
 * All rows are seeded with `custom = false` and `source = SOURCE`; they are immutable in the UI.
 */
internal data class FoodSeedRow(
    val name: String,
    val brand: String?,
    val carbsPer100g: Double,
    val gi: Double?,
    val category: String,
)

internal object FoodSeed {

    const val SOURCE: String = "USDA FDC (carbs) + ITGI 2008 (GI)"

    val ROWS: List<FoodSeedRow> = listOf(
        // ── Fruits ──────────────────────────────────────────────────────────────────────
        FoodSeedRow("Apple", "raw, with skin", 13.8, 36.0, "Fruit"),
        FoodSeedRow("Banana", "ripe", 22.8, 51.0, "Fruit"),
        FoodSeedRow("Banana", "under-ripe", 22.8, 42.0, "Fruit"),
        FoodSeedRow("Orange", "raw", 11.8, 43.0, "Fruit"),
        FoodSeedRow("Grapes", "raw", 18.1, 59.0, "Fruit"),
        FoodSeedRow("Strawberries", "raw", 7.7, 40.0, "Fruit"),
        FoodSeedRow("Blueberries", "raw", 14.5, 53.0, "Fruit"),
        FoodSeedRow("Watermelon", "raw", 7.6, 76.0, "Fruit"),
        FoodSeedRow("Cantaloupe", "raw", 8.2, 65.0, "Fruit"),
        FoodSeedRow("Pineapple", "raw", 13.1, 59.0, "Fruit"),
        FoodSeedRow("Mango", "raw", 15.0, 51.0, "Fruit"),
        FoodSeedRow("Peach", "raw", 9.5, 42.0, "Fruit"),
        FoodSeedRow("Pear", "raw", 15.2, 38.0, "Fruit"),
        FoodSeedRow("Cherries", "raw", 16.0, 22.0, "Fruit"),
        FoodSeedRow("Kiwifruit", "raw", 14.7, 53.0, "Fruit"),
        FoodSeedRow("Plum", "raw", 11.4, 40.0, "Fruit"),
        FoodSeedRow("Apricot", "raw", 11.1, 34.0, "Fruit"),
        FoodSeedRow("Grapefruit", "raw", 10.7, 25.0, "Fruit"),
        FoodSeedRow("Dates", "dried, Medjool", 75.0, 42.0, "Fruit"),
        FoodSeedRow("Raisins", "dried", 79.2, 64.0, "Fruit"),
        FoodSeedRow("Prunes", "dried", 63.9, 29.0, "Fruit"),
        FoodSeedRow("Figs", "dried", 63.9, 61.0, "Fruit"),
        // ── Fruit juices & beverages ────────────────────────────────────────────────────
        FoodSeedRow("Orange juice", "unsweetened", 10.4, 50.0, "Beverage"),
        FoodSeedRow("Apple juice", "unsweetened", 11.3, 41.0, "Beverage"),
        FoodSeedRow("Cranberry juice cocktail", null, 12.2, 68.0, "Beverage"),
        FoodSeedRow("Cola", "carbonated", 10.6, 63.0, "Beverage"),
        FoodSeedRow("Lemonade", null, 10.0, 54.0, "Beverage"),
        FoodSeedRow("Sports drink", "isotonic", 6.0, 78.0, "Beverage"),
        FoodSeedRow("Milk", "whole", 4.8, 39.0, "Dairy"),
        FoodSeedRow("Milk", "skim", 5.0, 37.0, "Dairy"),
        FoodSeedRow("Soy milk", "unsweetened", 3.3, 34.0, "Beverage"),
        FoodSeedRow("Beer", "lager", 3.6, 66.0, "Beverage"),
        // ── Breads ──────────────────────────────────────────────────────────────────────
        FoodSeedRow("White bread", "wheat", 49.0, 75.0, "Bread"),
        FoodSeedRow("Wholemeal bread", null, 41.0, 74.0, "Bread"),
        FoodSeedRow("Sourdough bread", "wheat", 47.0, 54.0, "Bread"),
        FoodSeedRow("Rye bread", "whole grain", 45.8, 50.0, "Bread"),
        FoodSeedRow("Pumpernickel", null, 44.0, 46.0, "Bread"),
        FoodSeedRow("Bagel", "plain", 53.0, 69.0, "Bread"),
        FoodSeedRow("Pita bread", "white", 55.7, 68.0, "Bread"),
        FoodSeedRow("Naan", null, 50.0, 71.0, "Bread"),
        FoodSeedRow("Tortilla", "wheat flour", 51.0, 30.0, "Bread"),
        FoodSeedRow("Tortilla", "corn", 44.6, 52.0, "Bread"),
        FoodSeedRow("Croissant", "butter", 45.8, 67.0, "Bakery"),
        FoodSeedRow("English muffin", null, 44.0, 77.0, "Bread"),
        FoodSeedRow("Baguette", "white", 55.0, 95.0, "Bread"),
        FoodSeedRow("Crackers", "water/soda", 71.0, 74.0, "Snack"),
        FoodSeedRow("Rice cakes", "plain", 81.5, 82.0, "Snack"),
        // ── Breakfast cereals ───────────────────────────────────────────────────────────
        FoodSeedRow("Cornflakes", null, 84.0, 81.0, "Cereal"),
        FoodSeedRow("Rice crisps cereal", null, 87.0, 89.0, "Cereal"),
        FoodSeedRow("Bran flakes", null, 80.0, 74.0, "Cereal"),
        FoodSeedRow("Muesli", null, 66.0, 57.0, "Cereal"),
        FoodSeedRow("Granola", null, 64.0, 55.0, "Cereal"),
        FoodSeedRow("Porridge oats", "rolled, cooked", 12.0, 55.0, "Cereal"),
        FoodSeedRow("Steel-cut oats", "cooked", 14.0, 52.0, "Cereal"),
        FoodSeedRow("Instant oatmeal", null, 68.0, 79.0, "Cereal"),
        FoodSeedRow("Shredded wheat", null, 76.0, 67.0, "Cereal"),
        FoodSeedRow("Wheat biscuit cereal", null, 69.0, 70.0, "Cereal"),
        // ── Grains, rice & pasta ────────────────────────────────────────────────────────
        FoodSeedRow("White rice", "long grain, boiled", 28.0, 73.0, "Grain"),
        FoodSeedRow("Basmati rice", "boiled", 25.0, 58.0, "Grain"),
        FoodSeedRow("Brown rice", "boiled", 23.0, 68.0, "Grain"),
        FoodSeedRow("Jasmine rice", "boiled", 28.0, 89.0, "Grain"),
        FoodSeedRow("Sticky rice", "glutinous, cooked", 32.0, 87.0, "Grain"),
        FoodSeedRow("Quinoa", "cooked", 21.3, 53.0, "Grain"),
        FoodSeedRow("Couscous", "cooked", 23.2, 65.0, "Grain"),
        FoodSeedRow("Bulgur", "cooked", 18.6, 48.0, "Grain"),
        FoodSeedRow("Pearl barley", "boiled", 28.2, 28.0, "Grain"),
        FoodSeedRow("Buckwheat", "cooked", 19.9, 45.0, "Grain"),
        FoodSeedRow("Spaghetti", "white, boiled", 25.0, 49.0, "Pasta"),
        FoodSeedRow("Spaghetti", "wholemeal, boiled", 27.0, 42.0, "Pasta"),
        FoodSeedRow("Macaroni", "boiled", 25.0, 47.0, "Pasta"),
        FoodSeedRow("Egg noodles", "boiled", 25.0, 40.0, "Pasta"),
        FoodSeedRow("Rice noodles", "boiled", 24.0, 53.0, "Pasta"),
        FoodSeedRow("Udon noodles", "boiled", 21.0, 55.0, "Pasta"),
        FoodSeedRow("Gnocchi", null, 32.0, 68.0, "Pasta"),
        // ── Starchy vegetables ──────────────────────────────────────────────────────────
        FoodSeedRow("Potato", "boiled", 20.1, 78.0, "Vegetable"),
        FoodSeedRow("Potato", "baked", 21.2, 85.0, "Vegetable"),
        FoodSeedRow("Potato", "mashed", 17.0, 83.0, "Vegetable"),
        FoodSeedRow("French fries", null, 41.0, 63.0, "Vegetable"),
        FoodSeedRow("Sweet potato", "boiled", 20.7, 63.0, "Vegetable"),
        FoodSeedRow("Sweet potato", "baked", 20.9, 94.0, "Vegetable"),
        FoodSeedRow("Sweet corn", "boiled", 20.5, 52.0, "Vegetable"),
        FoodSeedRow("Yam", "boiled", 27.9, 54.0, "Vegetable"),
        FoodSeedRow("Cassava", "boiled", 38.1, 46.0, "Vegetable"),
        FoodSeedRow("Plantain", "boiled", 31.9, 66.0, "Vegetable"),
        FoodSeedRow("Butternut squash", "cooked", 11.7, 51.0, "Vegetable"),
        FoodSeedRow("Parsnip", "boiled", 18.0, 52.0, "Vegetable"),
        FoodSeedRow("Beetroot", "boiled", 10.0, 64.0, "Vegetable"),
        // ── Non-starchy vegetables (low/near-zero carb) ─────────────────────────────────
        FoodSeedRow("Carrot", "boiled", 8.2, 39.0, "Vegetable"),
        FoodSeedRow("Green peas", "boiled", 14.5, 51.0, "Vegetable"),
        FoodSeedRow("Broccoli", "boiled", 7.2, null, "Vegetable"),
        FoodSeedRow("Cauliflower", "boiled", 4.1, null, "Vegetable"),
        FoodSeedRow("Spinach", "boiled", 3.8, null, "Vegetable"),
        FoodSeedRow("Tomato", "raw", 3.9, null, "Vegetable"),
        FoodSeedRow("Cucumber", "raw", 3.6, null, "Vegetable"),
        FoodSeedRow("Lettuce", "raw", 2.9, null, "Vegetable"),
        FoodSeedRow("Bell pepper", "raw", 6.0, null, "Vegetable"),
        FoodSeedRow("Onion", "raw", 9.3, null, "Vegetable"),
        FoodSeedRow("Mushrooms", "raw", 3.3, null, "Vegetable"),
        FoodSeedRow("Green beans", "boiled", 7.1, null, "Vegetable"),
        FoodSeedRow("Cabbage", "boiled", 5.5, null, "Vegetable"),
        FoodSeedRow("Zucchini", "cooked", 3.0, null, "Vegetable"),
        FoodSeedRow("Avocado", "raw", 8.5, null, "Vegetable"),
        // ── Legumes ─────────────────────────────────────────────────────────────────────
        FoodSeedRow("Chickpeas", "boiled", 27.4, 28.0, "Legume"),
        FoodSeedRow("Lentils", "boiled", 20.0, 32.0, "Legume"),
        FoodSeedRow("Red lentils", "boiled", 20.0, 26.0, "Legume"),
        FoodSeedRow("Kidney beans", "boiled", 22.8, 24.0, "Legume"),
        FoodSeedRow("Black beans", "boiled", 23.7, 30.0, "Legume"),
        FoodSeedRow("Pinto beans", "boiled", 26.2, 39.0, "Legume"),
        FoodSeedRow("Navy beans", "boiled", 21.0, 31.0, "Legume"),
        FoodSeedRow("Baked beans", "canned in sauce", 12.5, 40.0, "Legume"),
        FoodSeedRow("Soybeans", "boiled", 11.1, 16.0, "Legume"),
        FoodSeedRow("Hummus", null, 14.3, 6.0, "Legume"),
        FoodSeedRow("Peanuts", "roasted", 16.1, 14.0, "Legume"),
        // ── Dairy & eggs ────────────────────────────────────────────────────────────────
        FoodSeedRow("Yogurt", "plain, whole", 4.7, 36.0, "Dairy"),
        FoodSeedRow("Yogurt", "low-fat, fruit", 19.0, 41.0, "Dairy"),
        FoodSeedRow("Greek yogurt", "plain", 3.6, 11.0, "Dairy"),
        FoodSeedRow("Ice cream", "vanilla", 23.6, 57.0, "Dairy"),
        FoodSeedRow("Custard", "vanilla", 16.0, 43.0, "Dairy"),
        FoodSeedRow("Cheddar cheese", null, 1.3, null, "Dairy"),
        FoodSeedRow("Cottage cheese", null, 3.4, null, "Dairy"),
        FoodSeedRow("Egg", "whole", 1.1, null, "Protein"),
        // ── Proteins (near-zero carb) ───────────────────────────────────────────────────
        FoodSeedRow("Chicken breast", "cooked", 0.0, null, "Protein"),
        FoodSeedRow("Beef mince", "cooked", 0.0, null, "Protein"),
        FoodSeedRow("Salmon", "cooked", 0.0, null, "Protein"),
        FoodSeedRow("Tuna", "canned in water", 0.0, null, "Protein"),
        FoodSeedRow("Pork chop", "cooked", 0.0, null, "Protein"),
        FoodSeedRow("Tofu", "firm", 1.9, 15.0, "Protein"),
        FoodSeedRow("Bacon", "cooked", 1.4, null, "Protein"),
        FoodSeedRow("Sausage", "pork, cooked", 3.0, 28.0, "Protein"),
        // ── Snacks & sweets ─────────────────────────────────────────────────────────────
        FoodSeedRow("Potato chips", null, 53.0, 56.0, "Snack"),
        FoodSeedRow("Popcorn", "air-popped", 78.0, 65.0, "Snack"),
        FoodSeedRow("Pretzels", null, 80.0, 83.0, "Snack"),
        FoodSeedRow("Tortilla chips", null, 60.0, 63.0, "Snack"),
        FoodSeedRow("Milk chocolate", null, 59.0, 45.0, "Sweet"),
        FoodSeedRow("Dark chocolate", "70% cocoa", 46.0, 23.0, "Sweet"),
        FoodSeedRow("Digestive biscuit", null, 68.0, 62.0, "Sweet"),
        FoodSeedRow("Shortbread", null, 65.0, 64.0, "Sweet"),
        FoodSeedRow("Doughnut", "glazed", 51.0, 76.0, "Bakery"),
        FoodSeedRow("Muffin", "blueberry", 51.0, 59.0, "Bakery"),
        FoodSeedRow("Pancakes", null, 28.0, 66.0, "Bakery"),
        FoodSeedRow("Waffles", null, 33.0, 76.0, "Bakery"),
        FoodSeedRow("Sponge cake", "plain", 55.0, 46.0, "Bakery"),
        FoodSeedRow("Jelly beans", null, 93.0, 78.0, "Sweet"),
        FoodSeedRow("Marshmallows", null, 81.0, 62.0, "Sweet"),
        // ── Sugars & spreads ────────────────────────────────────────────────────────────
        FoodSeedRow("Table sugar", "sucrose", 100.0, 65.0, "Sugar"),
        FoodSeedRow("Glucose", "dextrose", 100.0, 100.0, "Sugar"),
        FoodSeedRow("Fructose", null, 100.0, 15.0, "Sugar"),
        FoodSeedRow("Honey", null, 82.4, 61.0, "Sugar"),
        FoodSeedRow("Maple syrup", null, 67.0, 54.0, "Sugar"),
        FoodSeedRow("Agave syrup", null, 76.0, 15.0, "Sugar"),
        FoodSeedRow("Jam", "strawberry", 65.0, 51.0, "Spread"),
        FoodSeedRow("Marmalade", null, 65.0, 48.0, "Spread"),
        FoodSeedRow("Peanut butter", null, 20.0, 14.0, "Spread"),
        FoodSeedRow("Nutella", "hazelnut spread", 57.0, 33.0, "Spread"),
        FoodSeedRow("Maltodextrin", null, 96.0, 108.0, "Sugar"),
        // ── Nuts & seeds ────────────────────────────────────────────────────────────────
        FoodSeedRow("Almonds", "raw", 21.6, 0.0, "Nut"),
        FoodSeedRow("Cashews", "roasted", 30.2, 22.0, "Nut"),
        FoodSeedRow("Walnuts", "raw", 13.7, 0.0, "Nut"),
        FoodSeedRow("Pistachios", "roasted", 27.5, 15.0, "Nut"),
        FoodSeedRow("Chia seeds", null, 42.1, 1.0, "Seed"),
        FoodSeedRow("Sunflower seeds", null, 20.0, 5.0, "Seed"),
        // ── Prepared / mixed dishes ─────────────────────────────────────────────────────
        FoodSeedRow("Pizza", "cheese & tomato", 26.0, 60.0, "Prepared"),
        FoodSeedRow("Cheeseburger", "with bun", 23.0, 66.0, "Prepared"),
        FoodSeedRow("Sushi", "salmon roll", 30.0, 55.0, "Prepared"),
        FoodSeedRow("Macaroni & cheese", null, 20.0, 64.0, "Prepared"),
        FoodSeedRow("Lasagna", "beef", 14.0, 52.0, "Prepared"),
        FoodSeedRow("Fried rice", null, 25.0, 68.0, "Prepared"),
        FoodSeedRow("Falafel", null, 32.0, 40.0, "Prepared"),
        FoodSeedRow("Spring roll", "fried", 30.0, 62.0, "Prepared"),
        FoodSeedRow("Tomato soup", "canned", 7.0, 38.0, "Prepared"),
        FoodSeedRow("Lentil soup", null, 10.0, 44.0, "Prepared"),
    )
}
