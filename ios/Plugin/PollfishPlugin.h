//
//  PollfishPlugin.h
//  Pollfish Plugin
//
//  Copyright (c) 2016 Corona Labs Inc. All rights reserved.
//

#ifndef _pollfish_h_
#define _pollfish_h_

#import "CoronaLua.h"
#import "CoronaMacros.h"

// This corresponds to the name of the library, e.g. [Lua] require "plugin.library"
// where the '.' is replaced with '_'
CORONA_EXPORT int luaopen_plugin_pollfish(lua_State *L);

#endif // _pollfish_h_
