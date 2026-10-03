package com.example.chatgpt_mealplanner.core.receipt

object AsdaPhysicalReceiptOcrFixture {

    fun createRealDeviceOcrResult(): ReceiptOcrResult {
        return createRealDevice79LineOcrResult()
    }

    fun createRealDevice79LineOcrResult(): ReceiptOcrResult {
        val lines = listOf(
            ReceiptOcrLine("ASDA SUPERSTORE", 0.95f, 0, left = 50, top = 100, right = 300, bottom = 120, centerY = 110, elements = listOf(ReceiptOcrElement("ASDA", 50, 100, 150, 120, 110), ReceiptOcrElement("SUPERSTORE", 160, 100, 300, 120, 110))),
            // Product 1: PIZZA 23.74 (OCR symbol corruption of £3.74 -> 23.74)
            ReceiptOcrLine("PIZZA", 0.95f, 1, left = 431, top = 1005, right = 538, bottom = 1025, centerY = 1015, elements = listOf(ReceiptOcrElement("PIZZA", 431, 1005, 538, 1025, 1015))),
            ReceiptOcrLine("23.74", 0.95f, 2, left = 1157, top = 1008, right = 1261, bottom = 1028, centerY = 1018, elements = listOf(ReceiptOcrElement("23.74", 1157, 1008, 1261, 1028, 1018))),
            // Product 2: PIZZA £3.74
            ReceiptOcrLine("PIZZA", 0.95f, 3, left = 431, top = 1047, right = 538, bottom = 1067, centerY = 1057, elements = listOf(ReceiptOcrElement("PIZZA", 431, 1047, 538, 1067, 1057))),
            ReceiptOcrLine("£3.74", 0.95f, 4, left = 1157, top = 1051, right = 1261, bottom = 1071, centerY = 1061, elements = listOf(ReceiptOcrElement("£3.74", 1157, 1051, 1261, 1071, 1061))),
            // Product 3: SNACKS £1.65
            ReceiptOcrLine("SNACKS", 0.95f, 5, left = 431, top = 1089, right = 550, bottom = 1109, centerY = 1099, elements = listOf(ReceiptOcrElement("SNACKS", 431, 1089, 550, 1109, 1099))),
            ReceiptOcrLine("£1.65", 0.95f, 6, left = 1157, top = 1093, right = 1261, bottom = 1113, centerY = 1103, elements = listOf(ReceiptOcrElement("£1.65", 1157, 1093, 1261, 1113, 1103))),
            // Product 4: CLEANING C12.00
            ReceiptOcrLine("CLEANING", 0.95f, 7, left = 431, top = 1132, right = 570, bottom = 1152, centerY = 1142, elements = listOf(ReceiptOcrElement("CLEANING", 431, 1132, 570, 1152, 1142))),
            ReceiptOcrLine("C12.00", 0.95f, 8, left = 1157, top = 1136, right = 1261, bottom = 1156, centerY = 1146, elements = listOf(ReceiptOcrElement("C12.00", 1157, 1136, 1261, 1156, 1146))),
            // Product 5: WEDGES L2.40
            ReceiptOcrLine("WEDGES", 0.95f, 9, left = 431, top = 1173, right = 550, bottom = 1193, centerY = 1183, elements = listOf(ReceiptOcrElement("WEDGES", 431, 1173, 550, 1193, 1183))),
            ReceiptOcrLine("L2.40", 0.95f, 10, left = 1157, top = 1179, right = 1261, bottom = 1199, centerY = 1189, elements = listOf(ReceiptOcrElement("L2.40", 1157, 1179, 1261, 1199, 1189))),
            // Product 6: PIZZA C1.90
            ReceiptOcrLine("PIZZA", 0.95f, 11, left = 431, top = 1216, right = 538, bottom = 1236, centerY = 1226, elements = listOf(ReceiptOcrElement("PIZZA", 431, 1216, 538, 1236, 1226))),
            ReceiptOcrLine("C1.90", 0.95f, 12, left = 1157, top = 1221, right = 1261, bottom = 1241, centerY = 1231, elements = listOf(ReceiptOcrElement("C1.90", 1157, 1221, 1261, 1241, 1231))),
            // Product 7: PIZZA £1.90
            ReceiptOcrLine("PIZZA", 0.95f, 13, left = 431, top = 1258, right = 538, bottom = 1278, centerY = 1268, elements = listOf(ReceiptOcrElement("PIZZA", 431, 1258, 538, 1278, 1268))),
            ReceiptOcrLine("£1.90", 0.95f, 14, left = 1157, top = 1264, right = 1261, bottom = 1284, centerY = 1274, elements = listOf(ReceiptOcrElement("£1.90", 1157, 1264, 1261, 1284, 1274))),
            // Product 8: DIPS £1.50
            ReceiptOcrLine("DIPS", 0.95f, 15, left = 431, top = 1300, right = 510, bottom = 1320, centerY = 1310, elements = listOf(ReceiptOcrElement("DIPS", 431, 1300, 510, 1320, 1310))),
            ReceiptOcrLine("£1.50", 0.95f, 16, left = 1157, top = 1306, right = 1261, bottom = 1326, centerY = 1316, elements = listOf(ReceiptOcrElement("£1.50", 1157, 1306, 1261, 1326, 1316))),
            // Product 9: ASDA DIP £1.50
            ReceiptOcrLine("ASDA DIP", 0.95f, 17, left = 431, top = 1341, right = 560, bottom = 1361, centerY = 1351, elements = listOf(ReceiptOcrElement("ASDA DIP", 431, 1341, 560, 1361, 1351))),
            ReceiptOcrLine("£1.50", 0.95f, 18, left = 1157, top = 1348, right = 1261, bottom = 1368, centerY = 1358, elements = listOf(ReceiptOcrElement("£1.50", 1157, 1348, 1261, 1368, 1358))),
            // Product 10: FINISH C5.75
            ReceiptOcrLine("FINISH", 0.95f, 19, left = 431, top = 1384, right = 540, bottom = 1404, centerY = 1394, elements = listOf(ReceiptOcrElement("FINISH", 431, 1384, 540, 1404, 1394))),
            ReceiptOcrLine("C5.75", 0.95f, 20, left = 1157, top = 1390, right = 1261, bottom = 1410, centerY = 1400, elements = listOf(ReceiptOcrElement("C5.75", 1157, 1390, 1261, 1410, 1400))),
            // Line 31 from physical v8 trace: WEDGES0N03HORe J3gH 92.40 (corrupted till code line)
            ReceiptOcrLine("WEDGES0N03HORe J3gH 92.40", 0.446f, 21, left = 428, top = 1408, right = 1263, bottom = 1460, centerY = 1434, elements = listOf(ReceiptOcrElement("WEDGES0N03HORe", 428, 1408, 897, 1460, 1434), ReceiptOcrElement("J3gH", 913, 1408, 1088, 1460, 1434), ReceiptOcrElement("92.40", 1156, 1408, 1263, 1460, 1434))),
            // Promotion Discount Line
            ReceiptOcrLine("4 FOR £5 FROZEN", 0.95f, 22, left = 431, top = 1480, right = 650, bottom = 1500, centerY = 1490, elements = listOf(ReceiptOcrElement("4 FOR £5 FROZEN", 431, 1480, 650, 1500, 1490))),
            ReceiptOcrLine("-£3.60", 0.95f, 23, left = 1157, top = 1485, right = 1261, bottom = 1505, centerY = 1495, elements = listOf(ReceiptOcrElement("-£3.60", 1157, 1485, 1261, 1505, 1495))),
            // Product 11: ASDA HAM £4.00 (with adjacent low-confidence corrupted price line "adno nolemaoinie g4.00")
            ReceiptOcrLine("adno nolemaoinie g4.00", 0.383f, 24, left = 763, top = 1510, right = 1271, bottom = 1530, centerY = 1519, elements = listOf(ReceiptOcrElement("adno", 763, 1510, 850, 1530, 1519), ReceiptOcrElement("nolemaoinie", 860, 1510, 1050, 1530, 1519), ReceiptOcrElement("g4.00", 1157, 1510, 1271, 1530, 1519))),
            ReceiptOcrLine("ASDA HAM", 0.704f, 25, left = 429, top = 1513, right = 604, bottom = 1533, centerY = 1523, elements = listOf(ReceiptOcrElement("ASDA", 429, 1513, 500, 1533, 1523), ReceiptOcrElement("HAM", 510, 1513, 604, 1533, 1523))),
            // Summary Totals Section
            ReceiptOcrLine("TOTAL:", 0.95f, 26, left = 431, top = 1700, right = 530, bottom = 1720, centerY = 1710, elements = listOf(ReceiptOcrElement("TOTAL:", 431, 1700, 530, 1720, 1710))),
            ReceiptOcrLine("£42.48", 0.95f, 27, left = 1157, top = 1700, right = 1261, bottom = 1720, centerY = 1710, elements = listOf(ReceiptOcrElement("£42.48", 1157, 1700, 1261, 1720, 1710))),
            // Savings line with -£3.60 before label in Y-sorted list
            ReceiptOcrLine("-£3.60", 0.95f, 28, left = 1160, top = 1746, right = 1287, bottom = 1766, centerY = 1756, elements = listOf(ReceiptOcrElement("-£3.60", 1160, 1746, 1287, 1766, 1756))),
            ReceiptOcrLine("o00a 00 ono 0JAL SAVIINGS:", 0.95f, 29, left = 419, top = 1754, right = 1039, bottom = 1774, centerY = 1764, elements = listOf(ReceiptOcrElement("o00a 00 ono 0JAL SAVIINGS:", 419, 1754, 1039, 1774, 1764))),
            ReceiptOcrLine("TOTALL:", 0.95f, 30, left = 431, top = 1790, right = 540, bottom = 1810, centerY = 1800, elements = listOf(ReceiptOcrElement("TOTALL:", 431, 1790, 540, 1810, 1800))),
            ReceiptOcrLine("e38.88", 0.95f, 31, left = 1157, top = 1795, right = 1261, bottom = 1815, centerY = 1805, elements = listOf(ReceiptOcrElement("e38.88", 1157, 1795, 1261, 1815, 1805))),
            ReceiptOcrLine("NO. ITEMS SOLD 12", 0.95f, 32, left = 431, top = 1830, right = 650, bottom = 1850, centerY = 1840, elements = listOf(ReceiptOcrElement("NO. ITEMS SOLD 12", 431, 1830, 650, 1850, 1840))),
            ReceiptOcrLine("CARD", 0.95f, 33, left = 431, top = 1870, right = 500, bottom = 1890, centerY = 1880, elements = listOf(ReceiptOcrElement("CARD", 431, 1870, 500, 1890, 1880))),
            ReceiptOcrLine("£38.88", 0.95f, 34, left = 1157, top = 1870, right = 1261, bottom = 1890, centerY = 1880, elements = listOf(ReceiptOcrElement("£38.88", 1157, 1870, 1261, 1890, 1880))),
            ReceiptOcrLine("TAX SUMMARY", 0.95f, 35, left = 431, top = 1940, right = 600, bottom = 1960, centerY = 1950, elements = listOf(ReceiptOcrElement("TAX SUMMARY", 431, 1940, 600, 1960, 1950))),
            ReceiptOcrLine("21.13", 0.95f, 36, left = 1157, top = 1970, right = 1261, bottom = 1990, centerY = 1980, elements = listOf(ReceiptOcrElement("21.13", 1157, 1970, 1261, 1990, 1980)))
        )

        return ReceiptOcrResult(
            lines = lines,
            fullText = lines.joinToString("\n") { it.text }
        )
    }
}
