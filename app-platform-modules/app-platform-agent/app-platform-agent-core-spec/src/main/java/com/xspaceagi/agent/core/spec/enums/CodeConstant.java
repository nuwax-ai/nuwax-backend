package com.xspaceagi.agent.core.spec.enums;

public class CodeConstant {

    public static final String DEFAULT_CODE_JS = """
            // Import JS plugins. Supports multiple forms: HTTP(s), npm packages, JSR ESM modules, Node built-in utilities, or search for needed plugins at https://deno.land/x
            // For network requests, you can use fetch directly. Refer to the fetch documentation for details.
            // Input: Parameters are uniformly wrapped in args, e.g., args.a, args.b
            // Output: Must be a JSON object, e.g., {message:"hello"} where the output key is "message"
            // Dependency examples:
            //import * as o from 'https://deno.land/x/cowsay/mod.ts'
            //import axios from 'npm:axios';
            //import { Buffer } from "node:buffer";
            //import { delay } from "jsr:@std/async";

            // First execution with dependencies may be slow and timeout. If it times out during test run, try again after a few minutes.
            // The entry function must not be modified, otherwise it cannot be executed. args are the configured input parameters.
            export default async function main(args) {
                // Build output object, keys must match configured output parameters.
                return {
                    'key': 'value',
                };
            }
            """;

    public static final String DEFAULT_CODE_PYTHON = """
            # Use import to bring in dependencies, or use importlib for dynamic imports.
            # Here is an example that creates a simple DataFrame:
            # import pandas as pd
            #
            #data = {
            #    'Name': ['Alice', 'Bob', 'Charlie'],
            #    'Age': [25, 30, 35]
            #}
            #df = pd.DataFrame(data)

            # First execution with dependencies may be slow and timeout. If it times out during test run, try again after a few minutes.
            # The entry function must not be modified, otherwise it cannot be executed. args are the configured input parameters.
            def main(args: dict) -> dict:

                params = args.get("params")
                # Build output object, keys must match configured output parameters.
                ret = {
                    "key": "value"
                }
                return ret
            """;

}
