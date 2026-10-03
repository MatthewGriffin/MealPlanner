# Barcode Lookup Architecture & Integration

This document defines the barcode scanning pipeline, local cache, and external product metadata lookup strategy.

---

## 1. Barcode Scanning Pipeline
- **Camera Framework**: CameraX (Preview & ImageAnalysis use cases).
- **Scanner Engine**: Google ML Kit Barcode Scanning (`BarcodeScannerOptions` for EAN-13, EAN-8, UPC-A, UPC-E).
- **Scanner Flow**:
  1. User opens Scanner screen (`CameraPreview`).
  2. Image analysis frame delivers decoded barcode string.
  3. UI transitions to `BARCODE_FOUND` state and vibrates/beeps.
  4. Repository executes `ProductLookupRepository.lookupBarcode(barcode)`.

---

## 2. Product Lookup Pipeline (4-Tier Architecture)

```
Barcode Decoded
       │
       ├──► Tier 1: Local Room Database Cache (ProductBarcodeMapping entity)
       │      │ Found? Return cached metadata.
       │      └─► Not Found
       │
       ├──► Tier 2: Open Food Facts API Adapter (External REST Lookup)
       │      │ Found? Save to ProductBarcodeMapping cache -> Return metadata.
       │      └─► Not Found
       │
       ├──► Tier 3: Retailer Product Database Search (Asda / Food Warehouse APIs)
       │      │ Found? Save to ProductBarcodeMapping cache -> Return metadata.
       │      └─► Not Found
       │
       └──► Tier 4: Manual Fallback
              User inputs display name, storage location, category, and quantity.
```

---

## 3. Product Normalization vs. Retail Product Distinction
- **Retail Product**: Represents the specific branded product bought at a store (e.g. "ASDA Mature Cheddar 400g" or "Cathedral City Mature Cheddar 350g").
- **Canonical Ingredient**: Maps to a standardized domain ingredient ID (e.g. `CHEDDAR_MATURE`).
- **Distinct Products Requirement**: Distinct canned goods (e.g. `BAKED_BEANS`, `BAKED_BEANS_WITH_SAUSAGES`, `CHILLI_BEANS`, `BBQ_BAKED_BEANS`) MUST map to separate canonical ingredients and NEVER be merged into a single generic "beans" category.
