-- Pollfish plugin

local Library = require "CoronaLibrary"

-- Create library
local lib = Library:new{ name="plugin.pollfish", publisherId="com.coronalabs", version=2 }

-------------------------------------------------------------------------------
-- BEGIN
-------------------------------------------------------------------------------

-- This sample implements the following Lua:
-- 
--    local PLUGIN_NAME = require "plugin_PLUGIN_NAME"
--    PLUGIN_NAME:showPopup()
--    

local function showWarning(functionName)
    print(functionName .. ": WARNING: The Pollfish plugin is only supported on Android & iOS devices. Please build for device")
end

function lib.init()
    showWarning("pollfish.init()")
end

function lib.isLoaded()
    showWarning("pollfish.isLoaded()")
end

function lib.load()
    showWarning("pollfish.load()")
end

function lib.show()
    showWarning("pollfish.show()")
end

function lib.hide()
    showWarning("pollfish.hide()")
end

function lib.setUserDetails()
    showWarning("pollfish.setUserDetails()")
end

-------------------------------------------------------------------------------
-- END
-------------------------------------------------------------------------------

-- Return an instance
return lib
