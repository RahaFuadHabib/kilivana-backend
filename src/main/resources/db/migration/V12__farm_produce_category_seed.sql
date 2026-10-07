-- Reference data: retain administrator-created identities and product references.
-- Serialize against category writes while checking normalized equivalents.
lock table categories in share row exclusive mode;

do $$
declare
    seed record;
    matching_id uuid;
    matches integer;
begin
    for seed in
        select * from (values
            ('c1200000-0000-4000-8000-000000000001'::uuid, 'Cereals & Grains'),
            ('c1200000-0000-4000-8000-000000000002'::uuid, 'Pulses & Legumes'),
            ('c1200000-0000-4000-8000-000000000003'::uuid, 'Vegetables'),
            ('c1200000-0000-4000-8000-000000000004'::uuid, 'Fruits'),
            ('c1200000-0000-4000-8000-000000000005'::uuid, 'Roots & Tubers'),
            ('c1200000-0000-4000-8000-000000000006'::uuid, 'Nuts & Oilseeds'),
            ('c1200000-0000-4000-8000-000000000007'::uuid, 'Herbs & Spices'),
            ('c1200000-0000-4000-8000-000000000008'::uuid, 'Coffee & Tea')
        ) as baseline(id, name)
    loop
        select count(*) into matches from categories
        where sector = 'FARM_PRODUCE'
          and lower(trim(regexp_replace(name, '\s+', ' ', 'g'))) = lower(seed.name);
        if matches > 1 then
            raise exception 'V12: ambiguous duplicate FARM_PRODUCE category: %; reconcile existing records before retrying', seed.name;
        elsif matches = 1 then
            select id into matching_id from categories
            where sector = 'FARM_PRODUCE'
              and lower(trim(regexp_replace(name, '\s+', ' ', 'g'))) = lower(seed.name);
            -- Preserve name, identity, creation time, and all references.
            update categories set active = true, version = version + 1, updated_at = now()
            where id = matching_id and active = false;
        else
            -- A conflicting fixed UUID fails the transaction; never overwrite it.
            insert into categories (id, version, created_at, updated_at, name, sector, active)
            values (seed.id, 0, now(), now(), seed.name, 'FARM_PRODUCE', true);
        end if;
    end loop;
end $$;
