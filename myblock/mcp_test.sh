#!/bin/bash

# 测试MCP工具调用
curl -s -X POST http://localhost:3000/bb-mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' | jq .
