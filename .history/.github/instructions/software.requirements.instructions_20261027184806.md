---
description: 'Use only when the user explicitly requests a formal requirements specification, stakeholder analysis, or scope document; not for routine implementation or bug fixes.'
---

# Software Requirements Definition Instructions

Use only the sections relevant to the requested document. This template does not select a chat agent, require a Plan/Architect handoff, or add an approval gate to unrelated work. Do not invent tools or require every checklist item for a small fix; preserve applicable project approval rules.

## Project Overview
**Project Name**: _[Project identifier]_  
**Type**: [ ] User Story [ ] Bug Fix [ ] Feature [ ] Enhancement [ ] Refactoring [ ] Migration [ ] POC/Spike  
**Title**: _[Brief description of the requirement]_  
**Priority**: [ ] Critical [ ] High [ ] Medium [ ] Low  
**Technology Stack**: _[e.g., Golang, Python, Frontend, Salesforce, databases, cloud services]_  
**Complexity**: [ ] Simple [ ] Medium [ ] Complex [ ] Very Complex  
**Requestor**: _[Team/Person requesting]_  
**Target Due Date**: _[If applicable]_  
**Dependencies**: _[External teams, services, or blockers]_

## Technology-Specific Requirements

### Go/Golang Projects
- [ ] Follow [Go Development Standards](go.development.instructions.md)
- [ ] Implement standard Go project layout (cmd/, internal/, pkg/, api/)
- [ ] Use Go 1.21+ features (generics, structured logging with slog)
- [ ] Include OTEL observability (tracing, metrics, logging)
- [ ] Implement proper error handling with sentinel errors and error wrapping
- [ ] Add comprehensive testing (unit, integration, benchmark tests)
- [ ] Use golangci-lint with strict configuration
- [ ] Include dependency injection patterns and interface-based design

### Python Projects  
- [ ] Follow [Python Standards & Best Practices](python.instructions.md)
- [ ] Target Python 3.10+ with proper version management
- [ ] Use pyproject.toml for dependency management
- [ ] Implement proper project layout with src/, tests/, docs/
- [ ] Include type hints and mypy static analysis
- [ ] Add comprehensive testing with pytest and coverage
- [ ] Use black, isort, and flake8 for code formatting and linting
- [ ] Include security scanning with bandit and safety

### Infrastructure/Terraform Projects
- [ ] Follow [Terraform Style Guide & Best Practices](terraform.instructions.md)
- [ ] Use Terraform 1.14.x+ with proper state management
- [ ] Implement modular design with reusable modules
- [ ] Include proper variable validation and output definitions
- [ ] Add terraform fmt, validate, and tflint checks
- [ ] Use remote state backend with state locking
- [ ] Include security scanning with tfsec or checkov

### Bash/Shell Scripts
- [ ] Follow [Bash Coding Instructions](bash.instructions.md)
- [ ] Use proper shebang (#!/bin/bash) and file permissions
- [ ] Implement error handling with set -euo pipefail
- [ ] Use proper quoting and variable expansion
- [ ] Add input validation and help documentation
- [ ] Include shellcheck static analysis

## Scope Definition
**Functional Scope**:
- [ ] Core features: _[List primary functionalities]_
- [ ] User stories count: _[Number and complexity]_
- [ ] API endpoints: _[New/modified endpoints]_
- [ ] Database changes: _[Schema updates, migrations]_
- [ ] UI/UX components: _[New screens, components]_
- [ ] Third-party integrations: _[External services, APIs]_

**Non-Functional Requirements**:
- [ ] Performance targets: _[Response time, throughput]_
- [ ] Security requirements: _[Authentication, authorization]_
- [ ] Scalability needs: _[Expected load, growth]_
- [ ] Compliance requirements: _[Regulatory, audit standards]_  

## Quality, Security, Performance Requirements

### Quality Standards
- [ ] Follow coding standards and style guides
- [ ] Code coverage minimum: ___% (recommended: 80%+)
- [ ] No code duplication – leverage reusable modules or libraries
- [ ] Proper abstraction and DRY principles
- [ ] Clear separation of concerns and comprehensive error handling
- [ ] Unit tests and integration tests for all new functionality
- [ ] Static code analysis passed (SonarQube, ESLint, etc.)
- [ ] Peer review approval required for all changes
- [ ] Documentation coverage for public APIs and complex logic

#### Go-Specific Quality Standards
- [ ] Follow Effective Go guidelines and idiomatic patterns
- [ ] Use gofmt, goimports, and golangci-lint with strict rules
- [ ] Implement proper interface design (small, focused interfaces)
- [ ] Use context.Context for cancellation and timeouts
- [ ] Implement proper error handling with typed errors
- [ ] Add benchmark tests for performance-critical paths
- [ ] Use table-driven tests and test helpers
- [ ] Include race detection tests (go test -race)

#### Python-Specific Quality Standards  
- [ ] Follow PEP 8 style guide and Python Enhancement Proposals
- [ ] Use black, isort, flake8, and mypy for code quality
- [ ] Include type hints for all public functions and classes
- [ ] Use pytest with fixtures and parametrized tests
- [ ] Implement proper logging with structured formats
- [ ] Add docstrings following Google or NumPy style
- [ ] Use virtual environments and dependency pinning

#### Infrastructure-Specific Quality Standards
- [ ] Use terraform fmt, validate, and plan before apply
- [ ] Implement proper module structure and versioning
- [ ] Add variable validation and meaningful descriptions
- [ ] Use consistent naming conventions and tagging
- [ ] Include proper outputs for module consumption
- [ ] Add terraform docs generation for modules

### Security Requirements  
- [ ] Secure handling of credentials and configuration
- [ ] No hardcoded secrets, API keys, or tokens
- [ ] Use environment variables or secret stores (e.g., Vault, AWS Secrets Manager)
- [ ] Input validation and sanitization for all user inputs
- [ ] Output encoding and CSRF protection where applicable
- [ ] Sensitive data masked in logs and audit trails
- [ ] Perform static code analysis and security scans (SAST, DAST, SCA)
- [ ] Apply principle of least privilege for access and integrations
- [ ] Vulnerability assessment and remediation plan
- [ ] Secure communication protocols (HTTPS, TLS)

#### Go-Specific Security Requirements
- [ ] Use gosec for security vulnerability scanning
- [ ] Implement proper JWT token validation and handling
- [ ] Use crypto/rand for cryptographically secure random numbers
- [ ] Implement rate limiting and circuit breaker patterns
- [ ] Use govulncheck for dependency vulnerability scanning
- [ ] Implement proper CORS and content security policies
- [ ] Use secure headers middleware for HTTP services

#### Python-Specific Security Requirements
- [ ] Use bandit for security linting and vulnerability detection
- [ ] Implement safety checks for known security vulnerabilities
- [ ] Use secure cookie settings and session management
- [ ] Implement proper SQL injection prevention (parameterized queries)
- [ ] Use pip-audit for dependency vulnerability scanning
- [ ] Implement secure file handling and path validation
- [ ] Use secure defaults for cryptographic operations

#### Infrastructure Security Requirements
- [ ] Use tfsec or checkov for Terraform security scanning
- [ ] Implement proper IAM roles and policies (least privilege)
- [ ] Enable encryption at rest and in transit
- [ ] Use secure parameter storage (AWS Systems Manager, etc.)
- [ ] Implement proper network security groups and NACLs
- [ ] Enable logging and monitoring for security events
- [ ] Use infrastructure scanning tools (Prowler, ScoutSuite)

### Performance Requirements
- [ ] Response time/feedback time within defined SLA: ___ms
- [ ] Database query optimization and indexing strategy
- [ ] Use caching/parallelism where applicable
- [ ] Optimize API/database calls and reduce bottlenecks
- [ ] Memory usage optimization and leak prevention
- [ ] Load testing for expected traffic patterns
- [ ] Optimize build and deployment strategies
- [ ] Efficient container/image handling (if applicable)
- [ ] Resource utilization monitoring and alerting  

#### Go-Specific Performance Requirements
- [ ] Use pprof for CPU and memory profiling
- [ ] Implement proper connection pooling for databases
- [ ] Use sync.Pool for object reuse in hot paths
- [ ] Add benchmark tests with performance regression detection
- [ ] Implement efficient JSON parsing and serialization
- [ ] Use channels and goroutines appropriately for concurrency
- [ ] Optimize garbage collection settings for workload
- [ ] Include OpenTelemetry metrics for observability

#### Python-Specific Performance Requirements
- [ ] Use cProfile and memory_profiler for performance analysis
- [ ] Implement connection pooling and async I/O where appropriate
- [ ] Use caching strategies (Redis, memcached) for frequently accessed data
- [ ] Optimize database queries with proper indexing and query analysis
- [ ] Use appropriate data structures and algorithms for the use case
- [ ] Implement lazy loading and pagination for large datasets
- [ ] Consider using compiled extensions (Cython, numba) for CPU-intensive tasks
- [ ] Monitor memory usage and implement proper cleanup

#### Infrastructure Performance Requirements
- [ ] Right-size compute resources based on actual usage patterns
- [ ] Implement auto-scaling policies for variable workloads
- [ ] Use appropriate instance types for workload characteristics
- [ ] Optimize network topology and reduce latency
- [ ] Implement CDN and edge caching where appropriate
- [ ] Monitor and optimize costs alongside performance
- [ ] Use managed services where they provide better performance/cost ratio  

## Requirements Analysis & Discovery Tasks
- [ ] Requirement gathering and scope definition
- [ ] Stakeholder interviews and clarifications
- [ ] Identify integration points (APIs, databases, external services)
- [ ] Define branch/versioning strategy
- [ ] Review existing components for reusability
- [ ] Document specific requirements and constraints
- [ ] Business objective alignment verification
- [ ] Acceptance criteria definition
- [ ] User persona and use case mapping

## Additional Considerations
**Technical Dependencies**: _[List any technical blockers or dependencies]_
**Business Constraints**: _[Budget, timeline, resource limitations]_
**Assumptions**: _[Key assumptions made during requirements gathering]_
**External Factors**: _[Third-party services, vendor dependencies, approvals]_
**Success Metrics**: _[How success will be measured]_