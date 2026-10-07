-- Top-left markers read by the WowForever app: magenta (x 0..1, rows 0..2) while an edit box has
-- keyboard focus; cyan (x 2..4, rows 0..1) always, meaning the in-game UI is loaded.

-- Parentless frames stay visible with the UI hidden and ignore UI scale.
local function marker(name, r, g, b)
    local f = CreateFrame("Frame", name)
    f:SetFrameStrata("TOOLTIP")
    f:SetFrameLevel(10000)
    f:SetScale(1)
    f:EnableMouse(false)
    local tex = f:CreateTexture(nil, "OVERLAY", nil, 7)
    tex:SetAllPoints(f)
    tex:SetColorTexture(r, g, b, 1)
    if tex.SetSnapToPixelGrid then tex:SetSnapToPixelGrid(false) end
    if tex.SetTexelSnappingBias then tex:SetTexelSnappingBias(0) end
    return f
end

local focusMarker = marker("WowForeverInputMarker", 1, 0, 1)
local worldMarker = marker("WowForeverWorldMarker", 0, 1, 1)

-- Places a marker in physical pixels (parentless frames use 768 units per screen height).
local function place(f, x, w, h)
    local _, sh = GetPhysicalScreenSize()
    if not sh or sh <= 0 then sh = 768 end
    local px = 768 / sh / f:GetEffectiveScale()
    f:ClearAllPoints()
    f:SetPoint("TOPLEFT", nil, "TOPLEFT", x * px, 0)
    f:SetSize(w * px, h * px)
end

local function layout()
    place(focusMarker, 0, 2, 3)
    place(worldMarker, 2, 3, 2)
end

local function hasTextFocus()
    local focus = GetCurrentKeyBoardFocus and GetCurrentKeyBoardFocus()
    if focus and (not focus.IsVisible or focus:IsVisible()) then return true end
    local chat = ChatEdit_GetActiveWindow and ChatEdit_GetActiveWindow()
    if chat and chat.HasFocus and chat:HasFocus() then return true end
    return false
end

layout()
focusMarker:Hide()
worldMarker:Show()

local ticker = CreateFrame("Frame")
local elapsed = 0
ticker:SetScript("OnUpdate", function(_, dt)
    elapsed = elapsed + dt
    if elapsed < 0.1 then return end
    elapsed = 0
    if hasTextFocus() then
        if not focusMarker:IsShown() then focusMarker:Show() end
    elseif focusMarker:IsShown() then
        focusMarker:Hide()
    end
end)
ticker:RegisterEvent("DISPLAY_SIZE_CHANGED")
ticker:RegisterEvent("UI_SCALE_CHANGED")
ticker:RegisterEvent("PLAYER_LOGIN")
ticker:SetScript("OnEvent", layout)
