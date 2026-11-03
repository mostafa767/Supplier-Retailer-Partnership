-- =====================================================================================
-- Demo data (only loaded by the running application, never by the tests).
-- Fixed ids so that the README's curl examples work as-is.
--
--   Marketplace A  11111111-...  "Swiss Pharmacy Network"
--     suppliers : Nivea (a1..01), La Roche-Posay (a1..02)
--     retailers : Pharmacie du Lac (a2..01, PHYSICAL), PharmaOnline (a2..02, ONLINE)
--   Marketplace B  22222222-...  "French Food Alliance"
--     suppliers : Danone (b1..01), Nivea (b1..02 - a SEPARATE account from A's Nivea)
--     retailers : Epicerie Martin (b2..01, PHYSICAL), FreshBox (b2..02, ONLINE)
--
-- Partnership dates are relative to the day the database is created, so the demo stays valid.
-- =====================================================================================

INSERT INTO marketplace (id, code, name) VALUES
    ('11111111-1111-1111-1111-111111111111', 'SPN', 'Swiss Pharmacy Network'),
    ('22222222-2222-2222-2222-222222222222', 'FFA', 'French Food Alliance');

INSERT INTO supplier (id, marketplace_id, company_name, contact_email) VALUES
    ('a1000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Nivea', 'trade@nivea.example'),
    ('a1000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'La Roche-Posay', 'trade@laroche.example'),
    ('b1000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'Danone', 'trade@danone.example'),
    ('b1000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Nivea', 'france@nivea.example');

INSERT INTO retailer (id, marketplace_id, name, store_type, website, address, city, contact_email, created_at) VALUES
    ('a2000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Pharmacie du Lac', 'PHYSICAL',
     NULL, 'Rue du Lac 12', 'Geneva', 'buyer@pharmacie-du-lac.example', now() - INTERVAL '1 day'),
    ('a2000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'PharmaOnline', 'ONLINE',
     'https://pharmaonline.example', NULL, NULL, 'buyer@pharmaonline.example', now() - INTERVAL '1 day'),
    ('b2000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'Epicerie Martin', 'PHYSICAL',
     NULL, '3 place du Marche', 'Lyon', 'contact@epicerie-martin.example', now() - INTERVAL '1 day'),
    ('b2000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'FreshBox', 'HYBRID',
     'https://freshbox.example', '8 rue Neuve', 'Paris', 'buyers@freshbox.example', now() - INTERVAL '1 day');

INSERT INTO partnership (id, marketplace_id, supplier_id, retailer_id, start_date, end_date) VALUES
    -- Marketplace A
    ('a3000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
     'a1000000-0000-0000-0000-000000000001', 'a2000000-0000-0000-0000-000000000001', CURRENT_DATE - 30, CURRENT_DATE + 335),
    ('a3000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111',
     'a1000000-0000-0000-0000-000000000001', 'a2000000-0000-0000-0000-000000000002', CURRENT_DATE - 30, CURRENT_DATE + 335),
    ('a3000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111',  -- SCHEDULED
     'a1000000-0000-0000-0000-000000000002', 'a2000000-0000-0000-0000-000000000002', CURRENT_DATE + 10, CURRENT_DATE + 400),
    -- Marketplace B
    ('b3000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222',
     'b1000000-0000-0000-0000-000000000001', 'b2000000-0000-0000-0000-000000000001', CURRENT_DATE - 30, CURRENT_DATE + 335),
    ('b3000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222',
     'b1000000-0000-0000-0000-000000000001', 'b2000000-0000-0000-0000-000000000002', CURRENT_DATE - 30, CURRENT_DATE + 335),
    ('b3000000-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222',
     'b1000000-0000-0000-0000-000000000002', 'b2000000-0000-0000-0000-000000000002', CURRENT_DATE - 30, CURRENT_DATE + 335);

INSERT INTO offer (id, marketplace_id, supplier_id, reference, title, discount_type, discount_value, currency,
                   valid_from, valid_to, status, published_at, created_at, updated_at) VALUES
    ('a4000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'a1000000-0000-0000-0000-000000000001',
     'NIV-SUN-01', 'Sun cream summer deal', 'PERCENTAGE', 15, NULL, CURRENT_DATE - 5, CURRENT_DATE + 60,
     'PUBLISHED', now() - INTERVAL '2 days', now() - INTERVAL '2 days', now() - INTERVAL '2 days'),
    ('a4000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'a1000000-0000-0000-0000-000000000001',
     'NIV-CARE-02', 'Body lotion bundle', 'FIXED_AMOUNT', 5.00, 'CHF', CURRENT_DATE - 5, CURRENT_DATE + 30,
     'PUBLISHED', now() - INTERVAL '2 days', now() - INTERVAL '2 days', now() - INTERVAL '2 days'),
    ('a4000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', 'a1000000-0000-0000-0000-000000000001',
     'NIV-DRAFT-03', 'Winter lip balm (draft)', 'PERCENTAGE', 10, NULL, CURRENT_DATE, CURRENT_DATE + 90,
     'DRAFT', NULL, now(), now()),
    ('b4000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'b1000000-0000-0000-0000-000000000001',
     'DAN-YOG-01', 'Yogurt 4-pack', 'PERCENTAGE', 20, NULL, CURRENT_DATE - 5, CURRENT_DATE + 45,
     'PUBLISHED', now() - INTERVAL '2 days', now() - INTERVAL '2 days', now() - INTERVAL '2 days');
